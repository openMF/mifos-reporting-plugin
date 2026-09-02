/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.eclipse.birt.report.engine.api.EngineException;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BirtReportLoader Tests")
class BirtReportLoaderTest {

    private static final String TENANT = "tenantA";

    @Mock
    private IReportEngine reportEngine;

    @Mock
    private BirtPluginProperties birtProperties;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ReportErrorHandler reportErrorHandler;

    @Mock
    private CacheManager cacheManager;

    private BirtReportLoader reportLoader;

    @TempDir
    Path tempDir;

    /**
     * The reports directory is a subdirectory, so that "outside" in these tests means outside it but
     * still inside what JUnit created and will delete. Resolving against the parent put fixed
     * filenames in the shared temporary root, where a parallel class could collide with them and
     * nothing would clean them up.
     */
    private Path reportsDir;

    /** A sibling of {@link #reportsDir}, so a name can genuinely try to climb out of it. */
    private Path outsideDir;

    @BeforeEach
    void setUp() throws EngineException, java.io.IOException {
        ThreadLocalContextUtil.setTenant(
                FineractPlatformTenant.builder().tenantIdentifier(TENANT).build());

        reportsDir = Files.createDirectories(tempDir.resolve("reports"));
        outsideDir = Files.createDirectories(tempDir.resolve("outside"));
        when(birtProperties.getReportsPath()).thenReturn(reportsDir.toString());
        reportLoader = new BirtReportLoader(
                reportEngine, new BirtReportsDirectory(birtProperties, jdbcTemplate), reportErrorHandler, cacheManager);

        // Mock error handler - will be used only in failure cases
        when(reportErrorHandler.reportError(anyString(), anyString())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0);
            String message = invocation.getArgument(1);
            throw new PlatformDataIntegrityException(code, message);
        });

        // Mock successful BIRT engine behavior
        IReportRunnable mockReport = mock(IReportRunnable.class);
        when(mockReport.getDesignHandle()).thenReturn(mock(org.eclipse.birt.report.model.api.ReportDesignHandle.class));
        when(reportEngine.openReportDesign(anyString())).thenReturn(mockReport);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.clearTenant();
    }

    @Test
    @DisplayName("Should load report successfully when file exists")
    void shouldLoadReportSuccessfully() throws Exception {
        Path reportPath = reportsDir.resolve("sample.rptdesign");
        Files.writeString(reportPath, "<?xml version=\"1.0\"?><report></report>");

        IReportRunnable report = reportLoader.loadReport("sample", null);
        assertNotNull(report);
    }

    @Test
    @DisplayName("Should throw PlatformDataIntegrityException when report not found")
    void shouldThrowExceptionWhenReportNotFound() {
        PlatformDataIntegrityException ex =
                assertThrows(PlatformDataIntegrityException.class, () -> reportLoader.loadReport("missing", null));

        assertEquals("error.msg.reporting.report.not.found", ex.getGlobalisationMessageCode());
    }

    @Test
    @DisplayName("Should prefer locale-specific report first")
    void shouldTryLocaleSpecificReportFirst() throws Exception {
        Path esReport = reportsDir.resolve("sample_es.rptdesign");
        Files.writeString(esReport, "<?xml version=\"1.0\"?><report lang=\"es\"></report>");

        IReportRunnable report = reportLoader.loadReport("sample", java.util.Locale.forLanguageTag("es"));
        assertNotNull(report);
    }

    @Test
    @DisplayName("Should prefer the tenant's own design over the shared one")
    void shouldPreferTenantDesignOverSharedDesign() throws Exception {
        Files.writeString(reportsDir.resolve("sample.rptdesign"), "<report>shared</report>");
        Path tenantDir = Files.createDirectories(reportsDir.resolve(TENANT));
        Path tenantDesign = tenantDir.resolve("sample.rptdesign");
        Files.writeString(tenantDesign, "<report>tenant</report>");

        reportLoader.loadReport("sample", null);

        ArgumentCaptor<String> openedPath = ArgumentCaptor.forClass(String.class);
        verify(reportEngine).openReportDesign(openedPath.capture());
        assertEquals(tenantDesign.toRealPath().toString(), openedPath.getValue());
    }

    /**
     * A design that exists is what makes this a confinement test: were the escaped target absent,
     * the loader would report it as not found without ever deciding whether it was allowed to read
     * it, and the assertion would pass while testing nothing.
     */
    @Test
    @DisplayName("Should refuse a report name that climbs out of the reports directory")
    void shouldRefuseReportNameEscapingTheReportsDirectory() throws Exception {
        Path escaped = outsideDir.resolve("escaped.rptdesign");
        Files.writeString(escaped, "<?xml version=\"1.0\"?><report></report>");
        assertTrue(Files.isRegularFile(escaped));

        String escapingName = "../" + outsideDir.getFileName() + "/escaped";

        PlatformDataIntegrityException ex =
                assertThrows(PlatformDataIntegrityException.class, () -> reportLoader.loadReport(escapingName, null));

        assertEquals("error.msg.reporting.report.not.found", ex.getGlobalisationMessageCode());
        verify(reportEngine, never()).openReportDesign(anyString());
    }

    /**
     * The tenant's own directory is what separates one tenant's designs from another's, so a nested
     * name that walks into another tenant's directory has to be refused rather than merely confined:
     * it stays inside the shared base directory, which containment alone would allow.
     */
    @Test
    @DisplayName("Should refuse a nested report name that names another tenant's directory")
    void shouldRefuseNestedReportNameNamingAnotherTenant() throws Exception {
        Path otherTenantDir = Files.createDirectories(reportsDir.resolve("tenantB"));
        Path otherDesign = otherTenantDir.resolve("Private.rptdesign");
        Files.writeString(otherDesign, "<?xml version=\"1.0\"?><report></report>");
        assertTrue(Files.isRegularFile(otherDesign));

        PlatformDataIntegrityException ex = assertThrows(
                PlatformDataIntegrityException.class, () -> reportLoader.loadReport("tenantB/Private", null));

        assertEquals("error.msg.reporting.report.not.found", ex.getGlobalisationMessageCode());
        verify(reportEngine, never()).openReportDesign(anyString());
    }

    @Test
    @DisplayName("Should reject an absolute report name")
    void shouldRejectAbsoluteReportName() throws Exception {
        Path outside = outsideDir.resolve("absolute.rptdesign");
        Files.writeString(outside, "<?xml version=\"1.0\"?><report></report>");

        String absoluteName = outside.toString().replace(".rptdesign", "");

        PlatformDataIntegrityException ex =
                assertThrows(PlatformDataIntegrityException.class, () -> reportLoader.loadReport(absoluteName, null));

        assertEquals("error.msg.reporting.report.not.found", ex.getGlobalisationMessageCode());
        verify(reportEngine, never()).openReportDesign(anyString());
    }

    /**
     * Normalising the text of the path is not enough on its own: a link inside the reports directory
     * resolves to wherever it points, and only following it shows that.
     */
    @Test
    @DisplayName("Should reject a report that is a symlink out of the reports directory")
    void shouldRejectSymlinkEscapingTheReportsDirectory() throws Exception {
        Path outside = outsideDir.resolve("linked.rptdesign");
        Files.writeString(outside, "<?xml version=\"1.0\"?><report></report>");

        try {
            Files.createSymbolicLink(reportsDir.resolve("linked.rptdesign"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks are not available here: " + e.getMessage());
        }

        PlatformDataIntegrityException ex =
                assertThrows(PlatformDataIntegrityException.class, () -> reportLoader.loadReport("linked", null));

        assertEquals("error.msg.reporting.report.not.found", ex.getGlobalisationMessageCode());
        verify(reportEngine, never()).openReportDesign(anyString());
    }

    /**
     * The reports directory comes from {@code c_external_service_properties}, so a value an
     * administrator stored can fail to be a path at all. That has to reach the caller as the same
     * not-found every other bad path does, not as an InvalidPathException escaping the loader.
     */
    @Test
    @DisplayName("Should reject a configured reports directory that is not a path")
    void shouldRejectAConfiguredReportsDirectoryThatIsNotAPath() {
        when(birtProperties.getReportsPath()).thenReturn("not a path");
        PlatformDataIntegrityException ex =
                assertThrows(PlatformDataIntegrityException.class, () -> reportLoader.loadReport("sample", null));

        assertEquals("error.msg.reporting.report.not.found", ex.getGlobalisationMessageCode());
    }

    @Test
    @DisplayName("Should not disclose the server filesystem path when a report is missing")
    void shouldNotLeakFilesystemPathWhenReportIsMissing() {
        PlatformDataIntegrityException ex =
                assertThrows(PlatformDataIntegrityException.class, () -> reportLoader.loadReport("missing", null));

        assertFalse(ex.getDefaultUserMessage().contains(reportsDir.toString()));
        assertFalse(ex.getDefaultUserMessage().contains(".rptdesign"));
    }

    @Test
    @DisplayName("Should key the design cache by tenant so one tenant cannot serve another's design")
    void shouldKeyCacheByTenant() {
        String tenantAKey = reportLoader.buildCacheKey("sample", null);

        ThreadLocalContextUtil.setTenant(
                FineractPlatformTenant.builder().tenantIdentifier("tenantB").build());
        String tenantBKey = reportLoader.buildCacheKey("sample", null);

        assertNotEquals(tenantAKey, tenantBKey);
    }

    /**
     * Tenant identifiers and report names may both contain the separator, so joining them with it is
     * not enough: {@code a} with {@code b_c} and {@code a_b} with {@code c} would be one key, and the
     * cache would hand one tenant the design compiled for another.
     */
    @Test
    @DisplayName("Should not collide when tenant and report names contain the key separator")
    void shouldNotCollideOnUnderscoresInTenantAndReportNames() {
        ThreadLocalContextUtil.setTenant(
                FineractPlatformTenant.builder().tenantIdentifier("a").build());
        String first = reportLoader.buildCacheKey("b_c", null);

        ThreadLocalContextUtil.setTenant(
                FineractPlatformTenant.builder().tenantIdentifier("a_b").build());
        String second = reportLoader.buildCacheKey("c", null);

        assertNotEquals(first, second);
    }
}
