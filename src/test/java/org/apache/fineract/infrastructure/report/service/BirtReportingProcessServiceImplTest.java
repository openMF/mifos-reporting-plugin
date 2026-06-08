/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.dataqueries.data.ReportExportType;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.apache.fineract.infrastructure.report.renderer.BirtRenderer;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("BirtReportingProcessServiceImpl Tests")
class BirtReportingProcessServiceImplTest {

  @Mock private IReportEngine reportEngine;
  @Mock private BirtReportExecutionFactory reportExecutionFactory;
  @Mock private BirtDataSourceConfigurer dataSourceConfigurer;
  @Mock private BirtParameterMapper parameterMapper;
  @Mock private BirtRenderer pdfRenderer;
  @Mock private BirtRenderer htmlRenderer;
  @Mock private BirtRenderer xlsRenderer;
  @Mock private BirtRenderer xlsxRenderer;
  @Mock private BirtRenderer csvRenderer;
  @Mock private BirtPluginProperties birtProperties;

  @InjectMocks private BirtReportingProcessServiceImpl service;

  @BeforeEach
  void setUp() {
    // Simulate Spring bean name-based injection of renderers
    Map<String, BirtRenderer> renderers =
        Map.of(
            "PDF", pdfRenderer,
            "HTML", htmlRenderer,
            "XLS", xlsRenderer,
            "XLSX", xlsxRenderer,
            "CSV", csvRenderer);
    ReflectionTestUtils.setField(service, "birtRenderers", renderers);

    // Use lenient() to avoid UnnecessaryStubbingException
    lenient().when(birtProperties.getDefaultLocale()).thenReturn("en");
  }

  private MultivaluedMap<String, String> queryParams(String outputType) {
    MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    if (outputType != null) {
      params.add("output-type", outputType);
    }
    return params;
  }

  @Test
  @DisplayName("Should extract only R_ prefixed parameters")
  void shouldExtractOnlyParamsWithR_Prefix() {
    MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.add("R_startDate", "15 May 2026");
    params.add("R_officeId", "1");
    params.add("R_clientId", "42");
    params.add("output-type", "PDF");
    params.add("ignoreThis", "value");

    Map<String, String> result = service.getReportParams(params);

    assertNotNull(result);
    assertEquals(3, result.size());
    assertEquals("15 May 2026", result.get("startDate"));
    assertEquals("1", result.get("officeId"));
    assertEquals("42", result.get("clientId"));
  }

  @Test
  @DisplayName("Should throw exception for invalid output type")
  void shouldThrowWhenOutputTypeIsInvalid() {
    PlatformDataIntegrityException ex =
        assertThrows(
            PlatformDataIntegrityException.class,
            () -> service.processRequest("sample", queryParams("INVALID")));

    assertEquals("error.msg.invalid.outputType", ex.getGlobalisationMessageCode());
  }

  @ParameterizedTest
  @CsvSource({
    "PDF, application/pdf",
    "HTML, text/html",
    "XLS, application/vnd.ms-excel",
    "XLSX, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "CSV, text/csv"
  })
  @DisplayName("Should support all major export formats")
  void shouldSupportAllExportFormats(String outputType, String expectedMimeType) throws Exception {
    IReportRunnable design = mock(IReportRunnable.class);
    ReportDesignHandle designHandle = mock(ReportDesignHandle.class);
    IRunAndRenderTask task = mock(IRunAndRenderTask.class);

    when(reportExecutionFactory.createExecutionRunnable(anyString(), any())).thenReturn(design);
    when(design.getDesignHandle()).thenReturn(designHandle);
    when(reportEngine.createRunAndRenderTask(design)).thenReturn(task);

    BirtRenderer renderer = getRendererForType(outputType);
    when(renderer.render(any(), anyString()))
        .thenReturn(Response.ok().type(expectedMimeType).build());

    doNothing().when(dataSourceConfigurer).configureAll(any());
    doNothing().when(parameterMapper).applyParameters(any(), any());

    Response response = service.processRequest("sample", queryParams(outputType));

    assertNotNull(response);
    assertEquals(200, response.getStatus());
    assertEquals(expectedMimeType, response.getMediaType().toString());
  }

  @Test
  @DisplayName("Should return correct list of supported export targets")
  void shouldReturnSupportedExportTargets() {
    List<ReportExportType> targets = service.getAvailableExportTargets();

    assertNotNull(targets);
    assertEquals(5, targets.size());
    assertTrue(targets.stream().anyMatch(t -> "PDF".equals(t.getKey())));
    assertTrue(targets.stream().anyMatch(t -> "XLS".equals(t.getKey())));
    assertTrue(targets.stream().anyMatch(t -> "XLSX".equals(t.getKey())));
    assertTrue(targets.stream().anyMatch(t -> "CSV".equals(t.getKey())));
    assertTrue(targets.stream().anyMatch(t -> "HTML".equals(t.getKey())));
  }

  @Test
  @DisplayName("Should throw when report file is not found")
  void shouldThrowWhenReportFileNotFound() {
    when(reportExecutionFactory.createExecutionRunnable(anyString(), any()))
        .thenThrow(
            new PlatformDataIntegrityException(
                "error.msg.reporting.report.not.found", "Report not found"));

    PlatformDataIntegrityException ex =
        assertThrows(
            PlatformDataIntegrityException.class,
            () -> service.processRequest("missing", queryParams("PDF")));

    assertEquals("error.msg.reporting.error", ex.getGlobalisationMessageCode());
  }

  @Test
  @DisplayName("Should call all collaborators in correct order")
  void shouldCallCollaboratorsInCorrectOrder() throws Exception {
    IReportRunnable design = mock(IReportRunnable.class);
    ReportDesignHandle designHandle = mock(ReportDesignHandle.class);
    IRunAndRenderTask task = mock(IRunAndRenderTask.class);

    when(reportExecutionFactory.createExecutionRunnable(anyString(), any())).thenReturn(design);
    when(design.getDesignHandle()).thenReturn(designHandle);
    when(reportEngine.createRunAndRenderTask(design)).thenReturn(task);
    when(pdfRenderer.render(any(), anyString())).thenReturn(Response.ok().build());

    service.processRequest("sample", queryParams("PDF"));

    verify(reportExecutionFactory).createExecutionRunnable(eq("sample"), any());
    verify(dataSourceConfigurer).configureAll(designHandle);
    verify(parameterMapper).applyParameters(eq(task), any());
    verify(pdfRenderer).render(eq(task), eq("sample"));
  }

  @Test
  @DisplayName("Should close BIRT task after rendering")
  void shouldCloseTaskAfterRendering() throws Exception {
    IReportRunnable design = mock(IReportRunnable.class);
    ReportDesignHandle designHandle = mock(ReportDesignHandle.class);
    IRunAndRenderTask task = mock(IRunAndRenderTask.class);

    when(reportExecutionFactory.createExecutionRunnable(anyString(), any())).thenReturn(design);
    when(design.getDesignHandle()).thenReturn(designHandle);
    when(reportEngine.createRunAndRenderTask(design)).thenReturn(task);
    when(pdfRenderer.render(any(), anyString())).thenReturn(Response.ok().build());

    service.processRequest("sample", queryParams("PDF"));

    verify(task).close();
  }

  private BirtRenderer getRendererForType(String outputType) {
    return switch (outputType.toUpperCase()) {
      case "PDF" -> pdfRenderer;
      case "HTML" -> htmlRenderer;
      case "CSV" -> csvRenderer;
      case "XLS" -> xlsRenderer;
      case "XLSX" -> xlsxRenderer;
      default -> htmlRenderer; // HTML
    };
  }
}
