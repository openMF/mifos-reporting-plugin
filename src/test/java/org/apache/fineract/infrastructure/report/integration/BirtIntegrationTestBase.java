/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BirtIntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(BirtIntegrationTestBase.class);

    /** The tenant Apache Fineract provisions for itself from the environment. */
    protected static final String DEFAULT_TENANT = "default";

    /**
     * A second tenant, registered below, so tests can prove that one tenant cannot reach another's
     * report designs. A single tenant cannot demonstrate isolation of anything.
     */
    protected static final String SECOND_TENANT = "second";

    protected static final String DEFAULT_TENANT_DB = "fineract_default";
    protected static final String SECOND_TENANT_DB = "fineract_second";

    /** Where the reports directory bound into the container lives on the host. */
    protected static final Path REPORTS_DIR =
            Paths.get("target", "it-birt-reports").toAbsolutePath();

    private static final String REPORTS_DIR_IN_CONTAINER = "/app/birt/reports";

    protected static final Network NETWORK = Network.newNetwork();

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("db")
            .withDatabaseName(DEFAULT_TENANT_DB)
            .withUsername("postgres")
            .withPassword("postgres");

    protected static GenericContainer<?> FINERACT;

    static {
        POSTGRES.start();

        psql("postgres", "CREATE DATABASE fineract_tenants;");

        seedReportsDirectory();

        /*
         * Apache Fineract creates the tenant store and the default tenant on its
         * first boot, so a second tenant cannot be registered before that has
         * happened. It is registered in between the two boots below, and the
         * restart is what makes Apache Fineract read it: tenant details are
         * resolved once at startup, so a tenant inserted into a running server is
         * not picked up.
         */
        FINERACT = buildFineract();
        FINERACT.start();

        registerSecondTenant();

        FINERACT.stop();
        FINERACT = buildFineract();
        FINERACT.start();
    }

    /**
     * Copies the report designs shipped in the repository into a writable directory under
     * {@code target/}.
     *
     * <p>The directory is bound read-write because installing a design is the feature under test.
     * Copying rather than binding {@code birt/reports} directly keeps an uploading test from writing
     * into the working tree, and leaves the shipped designs where the loader's fallback expects them
     * so the reports the other tests run are still found.
     */
    private static void seedReportsDirectory() {
        try {
            deleteRecursively(REPORTS_DIR);
            Files.createDirectories(REPORTS_DIR);

            Path shipped = Paths.get("birt", "reports").toAbsolutePath();
            try (Stream<Path> designs = Files.list(shipped)) {
                designs.filter(Files::isRegularFile).forEach(design -> {
                    try {
                        Files.copy(
                                design,
                                REPORTS_DIR.resolve(design.getFileName().toString()),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new IllegalStateException("Unable to stage report design " + design, e);
                    }
                });
            }

            // The container runs as its own user and has to be able to create the tenant directory.
            REPORTS_DIR.toFile().setWritable(true, false);
            REPORTS_DIR.toFile().setReadable(true, false);
            REPORTS_DIR.toFile().setExecutable(true, false);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to stage the BIRT reports directory", e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to clean " + path, e);
                }
            });
        }
    }

    private static GenericContainer<?> buildFineract() {
        String image = System.getProperty("fineract.it.image", "apache/fineract:develop");

        DockerImageName imageName = DockerImageName.parse(image).asCompatibleSubstituteFor("apache/fineract");

        GenericContainer<?> fineract = new GenericContainer<>(imageName)
                .withNetwork(NETWORK)
                .withExposedPorts(8443)
                .withEnv("FINERACT_HIKARI_JDBC_URL", "jdbc:postgresql://db:5432/fineract_tenants")
                .withEnv("FINERACT_HIKARI_USERNAME", "postgres")
                .withEnv("FINERACT_HIKARI_PASSWORD", "postgres")
                .withEnv("FINERACT_HIKARI_DRIVER_SOURCE_CLASS_NAME", "org.postgresql.Driver")
                .withEnv("FINERACT_DEFAULT_TENANTDB_HOSTNAME", "db")
                .withEnv("FINERACT_DEFAULT_TENANTDB_PORT", "5432")
                .withEnv("FINERACT_DEFAULT_TENANTDB_UID", "postgres")
                .withEnv("FINERACT_DEFAULT_TENANTDB_PWD", "postgres")
                .withEnv("FINERACT_DEFAULT_TENANTDB_CONN_PARAMS", "")
                .withEnv("TZ", "UTC")
                .withEnv("JAVA_TOOL_OPTIONS", "-Xmx2G")
                .withEnv("FINERACT_SERVER_SSL_ENABLED", "true")
                .withEnv("FINERACT_SERVER_PORT", "8443")
                // 1. Tell the plugin where to look for reports
                .withEnv("MIFOS_BIRT_REPORTS_PATH", REPORTS_DIR_IN_CONTAINER);

        // 2. Safely copy the ENTIRE directory of runtime dependencies gathered by Maven
        fineract.withCopyFileToContainer(MountableFile.forHostPath("target/test-runtime/libs"), "/app/birt/libs");

        // 3. Copy the plugin jar cleanly into the root of /app/birt with a safe fallback path
        String pluginJarPath = System.getProperty("birt.plugin.jar", "target/birt-plugin-1.15.0-SNAPSHOT.jar");
        fineract.withCopyFileToContainer(MountableFile.forHostPath(pluginJarPath), "/app/birt/birt-plugin.jar");

        // 4. Mount the staged reports directory, writable so designs can be installed into it
        fineract.withFileSystemBind(REPORTS_DIR.toString(), REPORTS_DIR_IN_CONTAINER, BindMode.READ_WRITE);

        // 5. Emulate Jib's classpath modification scheme to mount dependencies cleanly
        fineract.withCreateContainerCmdModifier(cmd -> {
            cmd.withEntrypoint(
                    "sh",
                    "-c",
                    "CLASSPATH=$(cat /app/jib-classpath-file) && "
                            + "exec java $JAVA_TOOL_OPTIONS "
                            + "-Duser.home=/tmp -Dfile.encoding=UTF-8 -Duser.timezone=UTC -Djava.security.egd=file:/dev/./urandom "
                            // FIX: Fineract's $CLASSPATH must come before BIRT's /libs/* to prevent library
                            // downgrades!
                            + "-cp /app/birt/birt-plugin.jar:$CLASSPATH:/app/birt/libs/* "
                            + "org.apache.fineract.ServerApplication");
            cmd.withCmd();
        });

        fineract.withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("fineract"))
                .waitingFor(Wait.forHttps("/fineract-provider/actuator/health")
                        .allowInsecure()
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(7)));

        return fineract;
    }

    /**
     * Registers a second tenant against a copy of the migrated default tenant database.
     *
     * <p>The connection row is copied from the default tenant's, which carries the encrypted schema
     * password and the master password hash it was encrypted under. Both are needed: without the
     * hash Apache Fineract fails to decrypt the password and refuses to start.
     */
    private static void registerSecondTenant() {
        psql("postgres", "CREATE DATABASE " + SECOND_TENANT_DB + ";");

        // pg_dump rather than a TEMPLATE copy, which Postgres refuses while Fineract holds
        // connections to the source database. bash and pipefail because the pipeline's status is
        // otherwise psql's alone: a pg_dump that failed would leave the copy incomplete, and the
        // registration would carry on against an empty database.
        Container.ExecResult copy = exec(
                POSTGRES,
                "bash",
                "-c",
                "set -o pipefail; pg_dump -U postgres " + DEFAULT_TENANT_DB
                        + " | psql -q -v ON_ERROR_STOP=1 -U postgres " + SECOND_TENANT_DB + " >/dev/null");
        if (copy.getExitCode() != 0) {
            throw new IllegalStateException(
                    "Copying " + DEFAULT_TENANT_DB + " into " + SECOND_TENANT_DB + " failed: " + copy.getStderr());
        }

        psql(
                "fineract_tenants",
                "INSERT INTO tenant_server_connections ("
                        + "  schema_server, schema_name, schema_server_port, schema_username, schema_password,"
                        + "  auto_update, master_password_hash)"
                        + " SELECT schema_server, '" + SECOND_TENANT_DB + "', schema_server_port, schema_username,"
                        + "  schema_password, auto_update, master_password_hash"
                        + " FROM tenant_server_connections WHERE id = 1;");

        psql(
                "fineract_tenants",
                "INSERT INTO tenants (identifier, name, timezone_id, oltp_id, report_id)"
                        + " SELECT '" + SECOND_TENANT + "', 'Second Tenant', timezone_id,"
                        + "  (SELECT MAX(id) FROM tenant_server_connections),"
                        + "  (SELECT MAX(id) FROM tenant_server_connections)"
                        + " FROM tenants WHERE id = 1;");
    }

    private static void psql(String database, String sql) {
        Container.ExecResult result = exec(POSTGRES, "psql", "-U", "postgres", "-d", database, "-c", sql);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("psql failed on " + database + ": " + result.getStderr());
        }
    }

    private static Container.ExecResult exec(GenericContainer<?> container, String... command) {
        try {
            return container.execInContainer(command);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(e);
        }
    }

    @BeforeAll
    void verifyContainerRunning() {
        if (!POSTGRES.isRunning()) {
            throw new IllegalStateException("Postgres container not running");
        }
        if (!FINERACT.isRunning()) {
            throw new IllegalStateException("Fineract container not running");
        }
    }

    protected static int getFineractPort() {
        return FINERACT.getMappedPort(8443);
    }

    /** The directory the plugin should store {@code tenant}'s uploaded designs in, on the host. */
    protected static Path tenantReportsDir(String tenant) {
        return REPORTS_DIR.resolve(tenant);
    }

    protected static Container.ExecResult execPostgres(String... command) {
        return exec(POSTGRES, command);
    }
}
