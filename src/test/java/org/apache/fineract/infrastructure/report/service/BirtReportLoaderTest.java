/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.eclipse.birt.report.engine.api.EngineException;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BirtReportLoader Tests")
class BirtReportLoaderTest {

  @Mock private IReportEngine reportEngine;
  @Mock private BirtPluginProperties birtProperties;
  @Mock private ReportErrorHandler reportErrorHandler;

  @InjectMocks private BirtReportLoader reportLoader;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() throws EngineException {
    when(birtProperties.getReportsPath()).thenReturn(tempDir.toString());

    // Mock error handler - will be used only in failure cases
    when(reportErrorHandler.reportError(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              String code = invocation.getArgument(0);
              String message = invocation.getArgument(1);
              throw new PlatformDataIntegrityException(code, message);
            });

    // Mock successful BIRT engine behavior
    IReportRunnable mockReport = mock(IReportRunnable.class);
    when(mockReport.getDesignHandle())
        .thenReturn(mock(org.eclipse.birt.report.model.api.ReportDesignHandle.class));
    when(reportEngine.openReportDesign(anyString())).thenReturn(mockReport);
  }

  @Test
  @DisplayName("Should load report successfully when file exists")
  void shouldLoadReportSuccessfully() throws Exception {
    Path reportPath = tempDir.resolve("sample.rptdesign");
    Files.writeString(reportPath, "<?xml version=\"1.0\"?><report></report>");

    IReportRunnable report = reportLoader.loadReport("sample", null);
    assertNotNull(report);
  }

  @Test
  @DisplayName("Should throw PlatformDataIntegrityException when report not found")
  void shouldThrowExceptionWhenReportNotFound() {
    PlatformDataIntegrityException ex =
        assertThrows(
            PlatformDataIntegrityException.class, () -> reportLoader.loadReport("missing", null));

    assertEquals("error.msg.reporting.report.not.found", ex.getGlobalisationMessageCode());
  }

  @Test
  @DisplayName("Should prefer locale-specific report first")
  void shouldTryLocaleSpecificReportFirst() throws Exception {
    Path esReport = tempDir.resolve("sample_es.rptdesign");
    Files.writeString(esReport, "<?xml version=\"1.0\"?><report lang=\"es\"></report>");

    IReportRunnable report =
        reportLoader.loadReport("sample", java.util.Locale.forLanguageTag("es"));
    assertNotNull(report);
  }
}
