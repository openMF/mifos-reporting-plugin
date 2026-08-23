/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Responsible for loading BIRT report designs (.rptdesign files). Supports locale-specific reports
 * and caching for better performance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BirtReportLoader {
    private final IReportEngine reportEngine;
    private final BirtPluginProperties birtProperties;
    private final ReportErrorHandler reportErrorHandler;
    private final ConcurrentMap<String, Long> reportModificationTimes = new ConcurrentHashMap<>();
    private final CacheManager cacheManager;
    private final JdbcTemplate jdbcTemplate;

    private static final String DEFAULT_REPORTS_DIR = System.getProperty("user.home")
            + File.separator
            + ".mifosx"
            + File.separator
            + "birtReports"
            + File.separator;
    private static final String CACHE_KEY = "#reportName + '_' + (#locale != null ? #locale.getLanguage() : 'en')";

    /**
     * Loads a BIRT report design with caching enabled.
     *
     * @param reportName the base name of the report (without extension)
     * @param locale the requested locale (can be null)
     * @return compiled IReportRunnable
     */
    @Cacheable(value = "birtReports", key = CACHE_KEY, sync = true)
    public IReportRunnable loadReport(String reportName, java.util.Locale locale) {

        String reportPath = buildReportPath(reportName, locale);

        log.info(
                "Cache miss for BIRT report: {} (locale: {}). Loading template from disk: {}",
                reportName,
                locale != null ? locale.getLanguage() : "en",
                reportPath);

        File reportFile = new File(reportPath);

        if (!reportFile.exists()) {
            log.error("Report design file not found: {}", reportPath);
            throw reportErrorHandler.reportError(
                    "error.msg.reporting.report.not.found", "Report file not found: " + reportName);
        }

        if (!reportFile.canRead()) {
            log.error("Report file exists but is not readable: {}", reportPath);
            throw reportErrorHandler.reportError(
                    "error.msg.reporting.report.not.readable", "Report file is not readable: " + reportName);
        }

        try {
            IReportRunnable reportRunnable = reportEngine.openReportDesign(reportPath);
            log.debug("Successfully loaded and parsed BIRT report: {}", reportName);
            return reportRunnable;

        } catch (Exception e) {
            log.error("Failed to open BIRT report design: {}", reportPath, e);
            throw reportErrorHandler.reportError(
                    "error.msg.reporting.report.load.failed", "Failed to load report design: " + reportName, e);
        }
    }

    public void validateTemplateFreshness(String reportName, java.util.Locale locale) {
        String reportPath = buildReportPath(reportName, locale);
        File reportFile = new File(reportPath);

        if (!reportFile.exists()) {
            evictCacheEntry(reportName, locale);
            log.info("Report template no longer exists on disk. Evicted cached version: {}", reportName);
            return;
        }

        String cacheKey = buildCacheKey(reportName, locale);
        long currentLastModified = reportFile.lastModified();
        Long cachedLastModified = reportModificationTimes.get(cacheKey);

        if (cachedLastModified != null && !cachedLastModified.equals(currentLastModified)) {
            log.info(
                    "Detected modification for report template: {} (locale: {}). Evicting cached version.",
                    reportName,
                    locale != null ? locale.getLanguage() : "en");
            evictCacheEntry(reportName, locale);
        }

        reportModificationTimes.put(cacheKey, currentLastModified);
    }

    private String buildCacheKey(String reportName, java.util.Locale locale) {
        return reportName + "_" + (locale != null ? locale.getLanguage() : "en");
    }

    private void evictCacheEntry(String reportName, java.util.Locale locale) {
        String cacheKey = buildCacheKey(reportName, locale);
        Cache cache = cacheManager.getCache("birtReports");
        if (cache != null) {
            cache.evict(cacheKey);
        }
        reportModificationTimes.remove(cacheKey);
        log.info(
                "Evicted cached BIRT report template: {} (locale: {})",
                reportName,
                locale != null ? locale.getLanguage() : "en");
    }

    /**
     * Builds the full path to the .rptdesign file, supporting locale-specific variants.
     *
     * <p>The report name reaches this method straight from the request path, so the resolved file has
     * to be confined to the reports directory: anything that escapes it is rejected as not found.
     */
    private String buildReportPath(String reportName, java.util.Locale locale) {
        final String languageTag = (locale != null && !"en".equalsIgnoreCase(locale.getLanguage()))
                ? "_" + locale.getLanguage().toLowerCase()
                : "";

        try {
            /*
             * The base directory is resolved here rather than before the try
             * because it comes from c_external_service_properties, so a value
             * an administrator stored can be no more a path than the report
             * name can. Both reach the caller as the same not-found.
             */
            final Path baseDir = realPath(
                    Paths.get(getBaseReportsDirectory()).toAbsolutePath().normalize());
            final Path reportPath = realPath(
                    baseDir.resolve(reportName + languageTag + ".rptdesign").normalize());
            if (reportPath.startsWith(baseDir)) {
                return reportPath.toString();
            }
        } catch (InvalidPathException e) {
            log.error(
                    "Rejected BIRT report [{}]: neither it nor the configured reports directory is a path",
                    reportName,
                    e);
            throw reportErrorHandler.reportError(
                    "error.msg.reporting.report.not.found", "Report file not found: " + reportName, e);
        }

        log.error("Rejected BIRT report name resolving outside the reports directory: {}", reportName);
        throw reportErrorHandler.reportError(
                "error.msg.reporting.report.not.found", "Report file not found: " + reportName);
    }

    /**
     * Resolves symlinks so the containment check cannot be walked around by a link inside the reports
     * directory. A path that does not exist yet has nothing to resolve, and stays as it is: the
     * caller reports it as not found either way.
     */
    private Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    /** Returns the base directory for BIRT reports. Priority: Database -> Properties -> Default */
    private String getBaseReportsDirectory() {
        // 1. Primary: Fetch from c_external_service_properties (Hot-swapping architecture)
        try {
            String sql = "SELECT p.value FROM c_external_service_properties p "
                    + "JOIN c_external_service s ON p.external_service_id = s.id "
                    + "WHERE s.name = 'BIRT' AND p.name = 'reports_dir'";
            String dbPath = jdbcTemplate.queryForObject(sql, String.class);
            if (StringUtils.isNotBlank(dbPath)) {
                log.info("BIRT reports directory loaded dynamically from database: {}", dbPath);
                return dbPath; // CRITICAL: This actually hands the path back to the engine!
            }
        } catch (Exception e) {
            log.debug("BIRT external service configuration not found in DB. Falling back to application properties.");
        }

        // 2. Secondary: Fallback to application.properties / ENV vars
        if (StringUtils.isNotBlank(birtProperties.getReportsPath())) {
            return birtProperties.getReportsPath();
        }

        // 3. Absolute Fallback
        return DEFAULT_REPORTS_DIR;
    }

    /**
     * Evicts a specific report template from cache.
     *
     * <p>This allows updated report definitions to be reloaded from disk on the next request instead
     * of continuing to use a cached template.
     */
    @CacheEvict(value = "birtReports", key = CACHE_KEY)
    public void evictFromCache(String reportName, java.util.Locale locale) {
        reportModificationTimes.remove(buildCacheKey(reportName, locale));
        log.info(
                "Evicting BIRT report template from cache: {} (locale: {})",
                reportName,
                locale != null ? locale.getLanguage() : "en");
    }
}
