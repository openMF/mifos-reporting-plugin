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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabasePasswordEncryptor;
import org.apache.fineract.infrastructure.dataqueries.data.ReportExportType;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.apache.fineract.infrastructure.report.renderer.BirtRenderer;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.engine.api.IRunTask;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("BirtReportingProcessServiceImpl Tests")
class BirtReportingProcessServiceImplTest {

    @Mock
    private IReportEngine reportEngine;

    @Mock
    private BirtReportExecutionFactory reportExecutionFactory;

    @Mock
    private BirtContextInjector contextInjector;

    @Mock
    private BirtParameterMapper parameterMapper;

    @Mock
    private BirtRenderer pdfRenderer;

    @Mock
    private BirtRenderer htmlRenderer;

    @Mock
    private BirtRenderer xlsRenderer;

    @Mock
    private BirtRenderer xlsxRenderer;

    @Mock
    private BirtRenderer csvRenderer;

    @Mock
    private BirtPluginProperties birtProperties;

    @Mock
    private DataSource dataSource;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private BirtSqlDialectInterpolator sqlDialectInterpolator;

    @Mock
    private DatabasePasswordEncryptor databasePasswordEncryptor;

    @Mock
    private BirtRenderer xmlRenderer;

    @Mock
    private ReportSecurityService reportSecurityService;

    @InjectMocks
    private BirtReportingProcessServiceImpl service;

    private MockedStatic<DataSourceUtils> mockedDataSourceUtils;
    private MockedStatic<ThreadLocalContextUtil> mockedThreadLocalContextUtil;
    private MockedStatic<org.apache.fineract.infrastructure.report.util.DataSourceUtils> mockedReportDataSourceUtils;

    private Connection mockConnection;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, BirtRenderer> renderers = Map.of(
                "PDF", pdfRenderer,
                "HTML", htmlRenderer,
                "XLS", xlsRenderer,
                "XLSX", xlsxRenderer,
                "CSV", csvRenderer,
                "XML", xmlRenderer);
        ReflectionTestUtils.setField(service, "birtRenderers", renderers);

        lenient().when(birtProperties.getDefaultLocale()).thenReturn("en");
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        mockConnection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        lenient().when(mockConnection.getMetaData()).thenReturn(metaData);
        lenient().when(metaData.getURL()).thenReturn("jdbc:postgresql://localhost:5432/fineract_tenant");

        mockedDataSourceUtils = mockStatic(DataSourceUtils.class);
        mockedDataSourceUtils
                .when(() -> DataSourceUtils.getConnection(any(DataSource.class)))
                .thenReturn(mockConnection);
        mockedDataSourceUtils
                .when(() -> DataSourceUtils.releaseConnection(any(Connection.class), any(DataSource.class)))
                .thenAnswer(i -> null);

        // Mock Tenant Context
        FineractPlatformTenant tenant = mock(FineractPlatformTenant.class);
        FineractPlatformTenantConnection tenantConnection = mock(FineractPlatformTenantConnection.class);
        lenient().when(tenant.getConnection()).thenReturn(tenantConnection);
        lenient().when(tenant.getTenantIdentifier()).thenReturn("default");
        lenient().when(tenantConnection.getSchemaUsername()).thenReturn("tenant_user");
        lenient().when(tenantConnection.getSchemaPassword()).thenReturn("encrypted_pass ");
        lenient().when(databasePasswordEncryptor.decrypt("encrypted_pass")).thenReturn("decrypted_pass");

        mockedThreadLocalContextUtil = mockStatic(ThreadLocalContextUtil.class);
        mockedThreadLocalContextUtil.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);

        mockedReportDataSourceUtils = mockStatic(org.apache.fineract.infrastructure.report.util.DataSourceUtils.class);
        mockedReportDataSourceUtils
                .when(() -> org.apache.fineract.infrastructure.report.util.DataSourceUtils.getDriverClassName(any()))
                .thenReturn("org.postgresql.Driver");
    }

    @AfterEach
    void tearDown() {
        if (mockedDataSourceUtils != null) {
            mockedDataSourceUtils.close();
        }
        if (mockedThreadLocalContextUtil != null) {
            mockedThreadLocalContextUtil.close();
        }
        if (mockedReportDataSourceUtils != null) {
            mockedReportDataSourceUtils.close();
        }
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

        Map<String, String> result = service.getReportParams("sample", params);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("15 May 2026", result.get("startDate"));
        assertEquals("1", result.get("officeId"));
        assertEquals("42", result.get("clientId"));
    }

    @Test
    @DisplayName("Should throw exception for invalid output type")
    void shouldThrowWhenOutputTypeIsInvalid() {
        PlatformDataIntegrityException ex = assertThrows(
                PlatformDataIntegrityException.class, () -> service.processRequest("sample", queryParams("INVALID")));

        assertEquals("error.msg.invalid.outputType", ex.getGlobalisationMessageCode());
    }

    @ParameterizedTest
    @CsvSource({
        "PDF, application/pdf",
        "HTML, text/html",
        "XLS, application/vnd.ms-excel",
        "XLSX, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "CSV, text/csv",
        "XML, application/xml"
    })
    @DisplayName("Should support all major export formats via streaming")
    void shouldSupportAllExportFormats(String outputType, String expectedMimeType) throws Exception {
        IReportRunnable design = mock(IReportRunnable.class);
        ReportDesignHandle designHandle = mock(ReportDesignHandle.class);
        IRunTask task = mock(IRunTask.class);
        HashMap<String, Object> appContext = new HashMap<>();

        when(task.getAppContext()).thenReturn(appContext);
        when(reportExecutionFactory.createExecutionRunnable(anyString(), any())).thenReturn(design);
        when(design.getDesignHandle()).thenReturn(designHandle);
        when(reportEngine.createRunTask(design)).thenReturn(task);

        BirtRenderer renderer = getRendererForType(outputType);
        when(renderer.render(eq(reportEngine), anyString(), anyString()))
                .thenReturn(Response.ok().type(expectedMimeType).build());

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
        assertEquals(6, targets.size());
        assertTrue(targets.stream().anyMatch(t -> "PDF".equals(t.getKey())));
        assertTrue(targets.stream().anyMatch(t -> "XLS".equals(t.getKey())));
        assertTrue(targets.stream().anyMatch(t -> "XLSX".equals(t.getKey())));
        assertTrue(targets.stream().anyMatch(t -> "CSV".equals(t.getKey())));
        assertTrue(targets.stream().anyMatch(t -> "HTML".equals(t.getKey())));
        assertTrue(targets.stream().anyMatch(t -> "XML".equals(t.getKey())));
    }

    @Test
    @DisplayName("Should preserve specific exception when report file is not found")
    void shouldThrowWhenReportFileNotFound() {
        when(reportExecutionFactory.createExecutionRunnable(anyString(), any()))
                .thenThrow(
                        new PlatformDataIntegrityException("error.msg.reporting.report.not.found", "Report not found"));

        PlatformDataIntegrityException ex = assertThrows(
                PlatformDataIntegrityException.class, () -> service.processRequest("missing", queryParams("PDF")));

        // After the fix we must NOT re-wrap PlatformDataIntegrityException
        assertEquals("error.msg.reporting.report.not.found", ex.getGlobalisationMessageCode());
        assertTrue(ex.getDefaultUserMessage().contains("Report not found"));
    }

    @Test
    @DisplayName("Should propagate missing required parameter exception without re-wrapping")
    void shouldPropagateMissingParameterException() throws Exception {
        IReportRunnable design = mock(IReportRunnable.class);
        ReportDesignHandle designHandle = mock(ReportDesignHandle.class);
        IRunTask task = mock(IRunTask.class);
        HashMap<String, Object> appContext = new HashMap<>();

        when(task.getAppContext()).thenReturn(appContext);
        when(reportExecutionFactory.createExecutionRunnable(anyString(), any())).thenReturn(design);
        when(design.getDesignHandle()).thenReturn(designHandle);
        when(reportEngine.createRunTask(design)).thenReturn(task);

        // Simulate the improved BirtParameterMapper throwing a specific missing-parameter exception
        doThrow(new PlatformDataIntegrityException(
                        "error.msg.reporting.missing.parameter", "Required parameter(s) not provided: branch"))
                .when(parameterMapper)
                .applyParameters(any(), any());

        PlatformDataIntegrityException ex = assertThrows(
                PlatformDataIntegrityException.class,
                () -> service.processRequest("Active_Loans_Details", queryParams("PDF")));

        // Critical: the specific code and message must reach the client
        assertEquals("error.msg.reporting.missing.parameter", ex.getGlobalisationMessageCode());
        assertTrue(ex.getDefaultUserMessage().contains("branch"));
    }

    @Test
    @DisplayName("Should call all collaborators and inject tenant context correctly")
    void shouldCallCollaboratorsInCorrectOrder() throws Exception {
        IReportRunnable design = mock(IReportRunnable.class);
        ReportDesignHandle designHandle = mock(ReportDesignHandle.class);
        IRunTask task = mock(IRunTask.class);
        HashMap<String, Object> appContext = new HashMap<>();

        when(task.getAppContext()).thenReturn(appContext);
        when(reportExecutionFactory.createExecutionRunnable(anyString(), any())).thenReturn(design);
        when(design.getDesignHandle()).thenReturn(designHandle);
        when(reportEngine.createRunTask(design)).thenReturn(task);
        when(pdfRenderer.render(eq(reportEngine), anyString(), anyString()))
                .thenReturn(Response.ok().build());

        service.processRequest("sample", queryParams("PDF"));

        // Verify the setConnectionDetail logic successfully populated the appContext
        assertEquals("org.postgresql.Driver", appContext.get("OdaJDBCDriverClass"));
        assertEquals("jdbc:postgresql://localhost:5432/fineract_tenant", appContext.get("OdaJDBCDriverUrl"));
        assertEquals("tenant_user", appContext.get("OdaJDBCDriverUser"));
        assertEquals("decrypted_pass", appContext.get("OdaJDBCDriverPassword"));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
                reportExecutionFactory, sqlDialectInterpolator, parameterMapper, contextInjector, task, pdfRenderer);

        inOrder.verify(reportExecutionFactory).createExecutionRunnable(eq("sample"), any());
        inOrder.verify(sqlDialectInterpolator).interpolate(designHandle);
        inOrder.verify(parameterMapper).applyParameters(eq(task), any());
        inOrder.verify(contextInjector).injectContextParameters(task);
        inOrder.verify(task).run(anyString());
        inOrder.verify(pdfRenderer).render(eq(reportEngine), anyString(), eq("sample"));
    }

    @Test
    @DisplayName("Should close BIRT run task after database execution completes")
    void shouldCloseTaskAfterRendering() throws Exception {
        IReportRunnable design = mock(IReportRunnable.class);
        ReportDesignHandle designHandle = mock(ReportDesignHandle.class);
        IRunTask task = mock(IRunTask.class);
        HashMap<String, Object> appContext = new HashMap<>();

        when(task.getAppContext()).thenReturn(appContext);
        when(reportExecutionFactory.createExecutionRunnable(anyString(), any())).thenReturn(design);
        when(design.getDesignHandle()).thenReturn(designHandle);
        when(reportEngine.createRunTask(design)).thenReturn(task);
        when(pdfRenderer.render(eq(reportEngine), anyString(), anyString()))
                .thenReturn(Response.ok().build());

        service.processRequest("sample", queryParams("PDF"));

        verify(task).close();
    }

    @Test
    @DisplayName("Should default to HTML when output type is missing")
    void shouldDefaultToHtmlWhenOutputTypeIsMissing() throws Exception {
        IReportRunnable design = mock(IReportRunnable.class);
        ReportDesignHandle designHandle = mock(ReportDesignHandle.class);
        IRunTask task = mock(IRunTask.class);
        HashMap<String, Object> appContext = new HashMap<>();

        when(task.getAppContext()).thenReturn(appContext);
        when(reportExecutionFactory.createExecutionRunnable(anyString(), any())).thenReturn(design);
        when(design.getDesignHandle()).thenReturn(designHandle);
        when(reportEngine.createRunTask(design)).thenReturn(task);
        when(htmlRenderer.render(eq(reportEngine), anyString(), anyString()))
                .thenReturn(Response.ok().type("text/html").build());

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        Response response = service.processRequest("sample", params);

        assertEquals(200, response.getStatus());
        verify(htmlRenderer).render(eq(reportEngine), anyString(), eq("sample"));
    }

    @Test
    @DisplayName("Should ignore blank report parameters")
    void shouldIgnoreBlankReportParameters() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("R_clientId", "");
        params.add("R_officeId", "1");

        Map<String, String> result = service.getReportParams("sample", params);

        assertEquals(1, result.size());
        assertEquals("1", result.get("officeId"));
    }

    @Test
    @DisplayName("Should close run task when rendering stream setup throws exception")
    void shouldCloseTaskWhenRendererThrowsException() throws Exception {
        IReportRunnable design = mock(IReportRunnable.class);
        ReportDesignHandle designHandle = mock(ReportDesignHandle.class);
        IRunTask task = mock(IRunTask.class);
        HashMap<String, Object> appContext = new HashMap<>();

        when(task.getAppContext()).thenReturn(appContext);
        when(reportExecutionFactory.createExecutionRunnable(anyString(), any())).thenReturn(design);
        when(design.getDesignHandle()).thenReturn(designHandle);
        when(reportEngine.createRunTask(design)).thenReturn(task);
        when(pdfRenderer.render(eq(reportEngine), anyString(), anyString()))
                .thenThrow(new RuntimeException("Renderer stream setup failure"));

        assertThrows(PlatformDataIntegrityException.class, () -> service.processRequest("sample", queryParams("PDF")));

        verify(task).close();
    }

    private BirtRenderer getRendererForType(String outputType) {
        return switch (outputType.toUpperCase()) {
            case "PDF" -> pdfRenderer;
            case "HTML" -> htmlRenderer;
            case "CSV" -> csvRenderer;
            case "XLS" -> xlsRenderer;
            case "XLSX" -> xlsxRenderer;
            case "XML" -> xmlRenderer;
            default -> htmlRenderer;
        };
    }
}
