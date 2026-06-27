package org.apache.fineract.infrastructure.report.integration;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  protected static final Network NETWORK = Network.newNetwork();

  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine")
          .withNetwork(NETWORK)
          .withNetworkAliases("db")
          .withDatabaseName("fineract_default")
          .withUsername("postgres")
          .withPassword("postgres");

  protected static GenericContainer<?> FINERACT;

  static {
    POSTGRES.start();

    try {
      POSTGRES.execInContainer("psql", "-U", "postgres", "-c", "CREATE DATABASE fineract_tenants;");
    } catch (Exception e) {
      throw new RuntimeException("Unable to create fineract_tenants database", e);
    }

    String image = System.getProperty("fineract.it.image", "apache/fineract:develop");

    DockerImageName imageName =
        DockerImageName.parse(image).asCompatibleSubstituteFor("apache/fineract");

    FINERACT =
        new GenericContainer<>(imageName)
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
            .withEnv("MIFOS_BIRT_REPORTS_PATH", "/app/birt/reports");

    // 2. Safely copy the ENTIRE directory of runtime dependencies gathered by Maven
    FINERACT.withCopyFileToContainer(
        MountableFile.forHostPath("target/test-runtime/libs"), "/app/birt/libs");

    // 3. Copy the plugin jar cleanly into the root of /app/birt to avoid overlapping file vs folder
    // conflicts
    FINERACT.withCopyFileToContainer(
        MountableFile.forHostPath(System.getProperty("birt.plugin.jar")),
        "/app/birt/birt-plugin.jar");

    // 4. Mount the actual report design file from your repo into the container
    FINERACT.withCopyFileToContainer(
        MountableFile.forHostPath("birt/reports/Active_Loans_Details.rptdesign"),
        "/app/birt/reports/Active_Loans_Details.rptdesign");

    // 5. Emulate Jib's classpath modification scheme to mount dependencies to Jib containers
    // cleanly
    // 5. Emulate Jib's classpath modification scheme to mount dependencies cleanly
    FINERACT.withCreateContainerCmdModifier(
        cmd -> {
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

    FINERACT
        .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("fineract"))
        .waitingFor(
            Wait.forHttps("/fineract-provider/actuator/health")
                .allowInsecure()
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(7)));

    FINERACT.start();
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

  protected static Container.ExecResult execPostgres(String... command) {
    try {
      return POSTGRES.execInContainer(command);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException(e);
    }
  }
}
