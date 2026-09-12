/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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
    private final BirtReportsDirectory reportsDirectory;
    private final ReportErrorHandler reportErrorHandler;
    private final ConcurrentMap<String, Long> reportModificationTimes = new ConcurrentHashMap<>();
    private final CacheManager cacheManager;

    /**
     * The design a name resolves to depends on the tenant, so the tenant belongs in the key. Without
     * it one tenant's compiled design is served to the next tenant that asks for the same name.
     */
    private static final String CACHE_KEY = "#root.target.buildCacheKey(#reportName, #locale)";

    /**
     * Loads a BIRT report design with caching enabled.
     *
     * @param reportName the base name of the report (without extension)
     * @param locale the requested locale (can be null)
     * @return compiled IReportRunnable
     */
    @Cacheable(value = "birtReports", key = CACHE_KEY, sync = true)
    public IReportRunnable loadReport(String reportName, java.util.Locale locale) {

        Optional<Path> resolved = resolveReportFile(reportName, locale);

        if (resolved.isEmpty()) {
            log.error("Report design file not found for report: {}", reportName);
            throw reportErrorHandler.reportError(
                    "error.msg.reporting.report.not.found", "Report file not found: " + reportName);
        }

        String reportPath = resolved.get().toString();

        log.info(
                "Cache miss for BIRT report: {} (locale: {}). Loading template from disk: {}",
                reportName,
                locale != null ? locale.getLanguage() : "en",
                reportPath);

        File reportFile = new File(reportPath);

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

        Optional<Path> resolved = resolveReportFile(reportName, locale);

        if (resolved.isEmpty()) {

            evictCacheEntry(reportName, locale);

            log.info("Report template no longer exists on disk. Evicted cached version: {}", reportName);

            return;
        }

        String cacheKey = buildCacheKey(reportName, locale);

        long currentLastModified = resolved.get().toFile().lastModified();

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

    /**
     * Public because the {@code @Cacheable} and {@code @CacheEvict} keys call it through
     * {@code #root.target}, which keeps one definition of the key instead of a SpEL copy that can
     * drift away from the Java one.
     */
    public String buildCacheKey(String reportName, java.util.Locale locale) {
        return segment(reportsDirectory.tenantIdentifier())
                + segment(reportName)
                + segment(locale != null ? locale.getLanguage() : "en");
    }

    /**
     * Joining the parts with a separator is not enough, because a tenant identifier and a report
     * name may both contain it: tenant {@code a} with report {@code b_c} would key the same entry as
     * tenant {@code a_b} with report {@code c}. Prefixing each part with its length keeps the key
     * unambiguous whatever the parts contain.
     */
    private String segment(String value) {
        return value.length() + "_" + value;
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
     * Resolves the .rptdesign file for a report, supporting locale-specific variants.
     *
     * <p>The report name arrives straight from the request path, so resolution is delegated to
     * {@link BirtReportsDirectory}, which looks in the tenant's own directory before the shared one
     * and confines the result to whichever it found: a name that escapes the reports tree resolves
     * to nothing and reaches the caller as a report that does not exist.
     */
    private Optional<Path> resolveReportFile(String reportName, java.util.Locale locale) {
        String languageTag = (locale != null && !"en".equalsIgnoreCase(locale.getLanguage()))
                ? "_" + locale.getLanguage().toLowerCase()
                : "";

        try {
            return reportsDirectory.resolveExisting(reportName + languageTag + ".rptdesign");
        } catch (InvalidPathException e) {
            log.error(
                    "Rejected BIRT report [{}]: neither it nor the configured reports directory is a path",
                    reportName,
                    e);
            return Optional.empty();
        }
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
