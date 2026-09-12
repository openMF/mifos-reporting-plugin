/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for where a tenant's BIRT report designs live, shared by the loader that
 * reads them and the upload service that writes them so the two cannot drift apart.
 *
 * <p>The base directory is resolved as it always was, from the {@code BIRT} external service's
 * {@code reports_dir} property, then {@code mifos.birt.reports-path} / {@code
 * MIFOS_BIRT_REPORTS_PATH}, falling back to {@code ~/.mifosx/birtReports/}. All three are process
 * wide, so <strong>the base directory is one directory shared by every tenant</strong>.
 *
 * <p>An upload therefore lands in a subdirectory named for the authenticated tenant, and a read
 * looks there before falling back to the base directory so the designs an administrator installed
 * by hand keep resolving. That subdirectory is what keeps one tenant's uploads out of another's
 * reach; without it there would be nothing separating them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BirtReportsDirectory {

    private static final String DEFAULT_REPORTS_DIR =
            System.getProperty("user.home") + File.separator + ".mifosx" + File.separator + "birtReports";

    private final BirtPluginProperties birtProperties;
    private final JdbcTemplate jdbcTemplate;

    /**
     * The tenant the current request authenticated as. Taken from the thread context Apache
     * Fineract® bound while authenticating, never from anything the caller sent.
     */
    public String tenantIdentifier() {
        final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
        if (tenant == null || StringUtils.isBlank(tenant.getTenantIdentifier())) {
            throw new IllegalStateException("No Apache Fineract tenant is bound to the current thread");
        }
        return tenant.getTenantIdentifier();
    }

    /** The configured base reports directory, shared by every tenant. */
    public Path base() {
        return Paths.get(configuredBaseDirectory()).toAbsolutePath().normalize();
    }

    /** The authenticated tenant's own reports directory, below {@link #base()}. */
    public Path tenantDirectory() {
        return base().resolve(tenantIdentifier()).normalize();
    }

    /**
     * Resolves a report file for reading: the tenant's own directory first, then the shared base
     * directory. Empty when the file is absent, or when the name does not resolve to a file directly
     * in one of them.
     */
    public Optional<Path> resolveExisting(final String fileName) {
        for (Path directory : List.of(tenantDirectory(), base())) {
            final Optional<Path> candidate = resolveWithin(directory, fileName);
            if (candidate.isPresent() && Files.isRegularFile(candidate.get())) {
                return candidate;
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves a report file for writing. A write only ever goes to the authenticated tenant's own
     * directory, which is created if it does not exist yet.
     *
     * <p>The name has to land directly in that directory. Containment alone would also admit {@code
     * nested/report.rptdesign}, which stays inside the tenant's tree but is not somewhere the loader
     * would ever look, so it is refused here rather than left to each caller.
     *
     * @throws IllegalArgumentException when the name does not resolve to a file directly in that
     *     directory
     * @throws IOException when the directory cannot be created
     */
    public Path resolveForWrite(final String fileName) throws IOException {
        final Path directory = tenantDirectory();
        Files.createDirectories(directory);

        return resolveWithin(directory, fileName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Report file name does not resolve to a file in the tenant reports directory: " + fileName));
    }

    /**
     * Resolves {@code fileName} to a file directly in {@code directory}. Symlinks are resolved first
     * so a link planted inside the directory cannot be used to step out of it; a path that does not
     * exist yet has nothing to resolve and is compared as it stands.
     *
     * <p>Containment on its own is not enough. It would also admit {@code nested/report.rptdesign},
     * and below the shared base directory the nested name is another tenant's own directory: {@code
     * tenantA/Private} read as one report name would hand tenant A's design to whoever asked. So the
     * name has to land directly in the directory, not merely somewhere under it.
     */
    private Optional<Path> resolveWithin(final Path directory, final String fileName) {
        final Path root = realPath(directory);
        final Path target = realPath(root.resolve(fileName).normalize());
        return root.equals(target.getParent()) ? Optional.of(target) : Optional.empty();
    }

    private Path realPath(final Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    /**
     * Priority: the {@code BIRT} external service's {@code reports_dir} property, so the directory
     * can be swapped without a restart, then the configured path, then the default directory.
     */
    private String configuredBaseDirectory() {
        try {
            final String sql = "SELECT p.value FROM c_external_service_properties p "
                    + "JOIN c_external_service s ON p.external_service_id = s.id "
                    + "WHERE s.name = 'BIRT' AND p.name = 'reports_dir'";
            final String dbPath = jdbcTemplate.queryForObject(sql, String.class);
            if (StringUtils.isNotBlank(dbPath)) {
                log.debug("BIRT reports directory loaded from the external service configuration: {}", dbPath);
                return dbPath;
            }
        } catch (Exception e) {
            log.debug("BIRT external service configuration not found. Falling back to application properties.");
        }

        if (StringUtils.isNotBlank(birtProperties.getReportsPath())) {
            return birtProperties.getReportsPath();
        }
        return DEFAULT_REPORTS_DIR;
    }
}
