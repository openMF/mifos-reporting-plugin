/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.dataqueries.data.ReportExportType;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BirtReportingProcessServiceImplTest {

    @Mock private IReportEngine reportEngine;
    @Mock private BirtReportLoader reportLoader;
    @Mock private BirtDataSourceConfigurer dataSourceConfigurer;
    @Mock private BirtParameterMapper parameterMapper;
    @Mock private BirtRenderer pdfRenderer;
    @Mock private BirtRenderer htmlRenderer;
    @Mock private BirtRenderer excelRenderer;
    @Mock private BirtRenderer csvRenderer;
    @Mock private BirtPluginProperties birtProperties;

    @InjectMocks
    private BirtReportingProcessServiceImpl service;

    @BeforeEach
    void setUp() {
        // Inject renderers map (Spring does this by bean name)
        Map<String, BirtRenderer> renderers = Map.of(
                "PDF", pdfRenderer,
                "HTML", htmlRenderer,
                "XLS", excelRenderer,
                "XLSX", excelRenderer,
                "CSV", csvRenderer
        );
        ReflectionTestUtils.setField(service, "birtRenderers", renderers);
    }

    private MultivaluedMap<String, String> queryParams(String outputType) {
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
        if (outputType != null) {
            map.add("output-type", outputType);
        }
        return map;
    }

    @Test
    void shouldExtractOnlyParamsWithR_Prefix() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("R_startDate", "2026-01-01");
        params.add("R_officeId", "1");
        params.add("output-type", "PDF");

        Map<String, String> result = service.getReportParams(params);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("2026-01-01", result.get("startDate"));
    }

    @Test
    void shouldThrowWhenOutputTypeIsInvalid() {
        PlatformDataIntegrityException ex = assertThrows(
                PlatformDataIntegrityException.class,
                () -> service.processRequest("any", queryParams("docx"))
        );
        assertEquals("error.msg.invalid.outputType", ex.getGlobalisationMessageCode());
    }

    @Test
    void shouldDefaultToHTMLWhenOutputTypeIsBlank() {
        assertThrows(PlatformDataIntegrityException.class,
                () -> service.processRequest("Report", queryParams("")));
    }

    @Test
    void shouldThrowWhenReportFileNotFound() {
        Mockito.when(reportLoader.loadReport(anyString(), any()))
           .thenThrow(new PlatformDataIntegrityException(
                   "error.msg.reporting.report.not.found", "Report not found"));

        PlatformDataIntegrityException ex = assertThrows(
                PlatformDataIntegrityException.class,
                () -> service.processRequest("missing", queryParams("PDF"))
        );

        assertEquals("error.msg.reporting.error", ex.getGlobalisationMessageCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PDF", "HTML", "XLS", "XLSX", "CSV"})
    void shouldSupportAllExportFormats(String outputType) throws Exception {
        IReportRunnable design = Mockito.mock(IReportRunnable.class);
        IRunAndRenderTask task = Mockito.mock(IRunAndRenderTask.class);
        BirtRenderer renderer = getRendererForType(outputType);

        Mockito.when(reportLoader.loadReport(anyString(), any())).thenReturn(design);
        Mockito.when(reportEngine.createRunAndRenderTask(design)).thenReturn(task);
        Mockito.doNothing().when(dataSourceConfigurer).configureAll(any());
        Mockito.doNothing().when(parameterMapper).applyParameters(any(), any());

        // Return valid Response
        Mockito.when(renderer.render(any(), anyString()))
                .thenReturn(Response.ok().type(getMimeType(outputType)).build());

        Response response = service.processRequest("TestReport", queryParams(outputType));

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        Mockito.verify(renderer).render(any(), eq("TestReport"));
    }

    private BirtRenderer getRendererForType(String type) {
        return switch (type) {
            case "PDF" -> pdfRenderer;
            case "HTML" -> htmlRenderer;
            case "CSV" -> csvRenderer;
            default -> excelRenderer;
        };
    }

    private String getMimeType(String type) {
        return switch (type) {
            case "PDF" -> "application/pdf";
            case "HTML" -> "text/html";
            case "CSV" -> "text/csv";
            default -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        };
    }

    @Test
    void shouldReturnSupportedExportTargets() {
        List<ReportExportType> targets = service.getAvailableExportTargets();

        assertNotNull(targets);
    }

    @Test
    void shouldCallCollaboratorsInCorrectOrder() throws Exception {
        IReportRunnable design = Mockito.mock(IReportRunnable.class);
        IRunAndRenderTask task = Mockito.mock(IRunAndRenderTask.class);

        Mockito.when(reportLoader.loadReport(anyString(), any())).thenReturn(design);
        Mockito.when(reportEngine.createRunAndRenderTask(design)).thenReturn(task);
        Mockito.when(pdfRenderer.render(any(), anyString())).thenReturn(Response.ok().build());

        service.processRequest("Report", queryParams("PDF"));

        Mockito.verify(reportLoader).loadReport(eq("Report"), any());
        Mockito.verify(dataSourceConfigurer).configureAll(any());
        Mockito.verify(parameterMapper).applyParameters(eq(task), any());
        Mockito.verify(pdfRenderer).render(eq(task), eq("Report"));
    }
}