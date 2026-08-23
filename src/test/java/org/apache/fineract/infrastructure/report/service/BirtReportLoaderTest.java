/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.eclipse.birt.report.engine.api.EngineException;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BirtReportLoader Tests")
class BirtReportLoaderTest {

    @Mock
    private IReportEngine reportEngine;

    @Mock
    private BirtPluginProperties birtProperties;

    @Mock
    private ReportErrorHandler reportErrorHandler;

    @InjectMocks
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

    @BeforeEach
    void setUp() throws EngineException, java.io.IOException {
        reportsDir = tempDir.resolve("reports");
        Files.createDirectories(reportsDir);
        when(birtProperties.getReportsPath()).thenReturn(reportsDir.toString());

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
    @DisplayName("Should reject a report name that climbs out of the reports directory")
    void shouldRejectReportNameEscapingTheReportsDirectory() throws Exception {
        Path outside = tempDir.resolve("outside.rptdesign");
        Files.writeString(outside, "<?xml version=\"1.0\"?><report></report>");

        PlatformDataIntegrityException ex =
                assertThrows(PlatformDataIntegrityException.class, () -> reportLoader.loadReport("../outside", null));

        assertEquals("error.msg.reporting.report.not.found", ex.getGlobalisationMessageCode());
        verify(reportEngine, never()).openReportDesign(anyString());
    }

    @Test
    @DisplayName("Should reject an absolute report name")
    void shouldRejectAbsoluteReportName() throws Exception {
        Path outside = tempDir.resolve("absolute.rptdesign");
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
        Path outside = tempDir.resolve("linked.rptdesign");
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
        when(birtProperties.getReportsPath()).thenReturn("not\u0000a\u0000path");
        // this path reports through the overload that carries the cause, so it needs its own stub
        when(reportErrorHandler.reportError(anyString(), anyString(), any())).thenAnswer(invocation -> {
            final String code = invocation.getArgument(0);
            final String message = invocation.getArgument(1);
            throw new PlatformDataIntegrityException(code, message);
        });

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
}
