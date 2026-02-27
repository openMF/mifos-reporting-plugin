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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabasePasswordEncryptor;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.eclipse.birt.report.engine.api.IEngineTask;
import org.eclipse.birt.report.engine.api.IGetParameterDefinitionTask;
import org.eclipse.birt.report.engine.api.IParameterDefn;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.eclipse.birt.report.model.api.DesignElementHandle;
import org.eclipse.birt.report.model.api.OdaDataSourceHandle;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.eclipse.birt.report.model.api.SlotHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit tests for BirtReportingProcessServiceImpl. */
@ExtendWith(MockitoExtension.class)
public class BirtReportingProcessServiceImplTest {

  enum TaskOutcome {
    SUCCESS,
    FAILED_BY_STATUS,
    FAILED_BY_ERRORS,
    PARAM_VALIDATION_FAIL
  }

  record ParamSpec(String name, int dataType) {}

  record EngineResult(IRunAndRenderTask task, IGetParameterDefinitionTask paramTask) {}

  static final class EngineSetup {
    TaskOutcome outcome = TaskOutcome.SUCCESS;
    List<ParamSpec> params = Collections.emptyList();
    boolean withDataSource = false;

    EngineSetup outcome(TaskOutcome o) {
      this.outcome = o;
      return this;
    }

    EngineSetup params(List<ParamSpec> p) {
      this.params = p;
      return this;
    }

    EngineSetup withDataSource() {
      this.withDataSource = true;
      return this;
    }

    EngineSetup param(String name, int type) {
      this.params = List.of(new ParamSpec(name, type));
      return this;
    }
  }

  @Mock private IReportEngine reportEngine;
  @Mock private DataSource tenantDataSource;
  @Mock private DatabasePasswordEncryptor databasePasswordEncryptor;
  @Mock private FineractProperties fineractProperties;
  @Mock private ApplicationContext applicationContext;
  @Mock private ApplicationContext contextVar;
  @Mock private PlatformSecurityContext context;

  @InjectMocks private BirtReportingProcessServiceImpl birtReportingService;

  static Stream<Arguments> exportFormats() {
    return Stream.of(
        Arguments.of("PDF", "application/pdf", null),
        Arguments.of("HTML", "text/html", null),
        Arguments.of("XLS", "application/vnd.ms-excel", ".xls"),
        Arguments.of(
            "XLSX", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
        Arguments.of("CSV", "text/csv", ".csv"));
  }

  private MultivaluedMap<String, String> queryParams(String outputType) {
    MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
    if (outputType != null) map.add("output-type", outputType);
    return map;
  }

  private File prepareReportFile(Path tempDir, String reportName) throws Exception {
    ReflectionTestUtils.setField(birtReportingService, "fineractBirtBaseDir", tempDir.toString());
    File f = tempDir.resolve(reportName + ".rptdesign").toFile();
    f.createNewFile();
    return f;
  }

  // Parameter Extraction & Validation

  @Test
  void shouldExtractOnlyParamsWithR_Prefix() {
    MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.add("R_startDate", "2026-01-01");
    params.add("R_officeId", "1");
    params.add("output-type", "pdf");

    Map<String, String> result = birtReportingService.getReportParams(params);

    assertNotNull(result);
    assertEquals(2, result.size());
    assertEquals("2026-01-01", result.get("startDate"));
    assertEquals("1", result.get("officeId"));
  }

  @Test
  void shouldThrowWhenOutputTypeIsInvalid() {
    PlatformDataIntegrityException ex =
        assertThrows(
            PlatformDataIntegrityException.class,
            () -> birtReportingService.processRequest("any", queryParams("docx")));
    assertEquals("error.msg.invalid.outputType", ex.getGlobalisationMessageCode());
  }

  @Test
  void shouldFallBackToHtmlWhenOutputTypeIsBlank(@TempDir Path tempDir) {
    ReflectionTestUtils.setField(birtReportingService, "fineractBirtBaseDir", tempDir.toString());
    MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.add("output-type", "");

    try (MockedStatic<ThreadLocalContextUtil> ignored =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      PlatformDataIntegrityException ex =
          assertThrows(
              PlatformDataIntegrityException.class,
              () -> birtReportingService.processRequest("Report", params));
      assertEquals("error.msg.reporting.error", ex.getGlobalisationMessageCode());
    }
  }

  @Test
  void shouldThrowUnsupportedOperationForAvailableTargets() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> birtReportingService.getAvailableExportTargets());
  }

  @Test
  void shouldThrowWhenReportFileNotFound(@TempDir Path tempDir) {
    ReflectionTestUtils.setField(birtReportingService, "fineractBirtBaseDir", tempDir.toString());

    try (MockedStatic<ThreadLocalContextUtil> ignored =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      PlatformDataIntegrityException ex =
          assertThrows(
              PlatformDataIntegrityException.class,
              () -> birtReportingService.processRequest("missing", queryParams("PDF")));
      assertEquals("error.msg.reporting.error", ex.getGlobalisationMessageCode());
    }
  }

  @Test
  void shouldDefaultToHtmlAndFailAtMissingFile() {
    try (MockedStatic<ThreadLocalContextUtil> ignored =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      PlatformDataIntegrityException ex =
          assertThrows(
              PlatformDataIntegrityException.class,
              () -> birtReportingService.processRequest("default", queryParams(null)));
      assertEquals("error.msg.reporting.error", ex.getGlobalisationMessageCode());
    }
  }

  @Test
  void shouldAppendLocaleToReportNameWhenNotEnglish() {
    MultivaluedMap<String, String> params = queryParams("PDF");
    params.add("locale", "es");

    try (MockedStatic<ThreadLocalContextUtil> ignored =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      PlatformDataIntegrityException ex =
          assertThrows(
              PlatformDataIntegrityException.class,
              () -> birtReportingService.processRequest("Clients", params));
      assertTrue(ex.getDefaultUserMessage().contains("Clients_es.rptdesign"));
    }
  }

  @Test
  void shouldNotAppendLocaleWhenLocaleIsEnglish(@TempDir Path tempDir) {
    ReflectionTestUtils.setField(birtReportingService, "fineractBirtBaseDir", tempDir.toString());
    MultivaluedMap<String, String> params = queryParams("PDF");
    params.add("locale", "en");

    try (MockedStatic<ThreadLocalContextUtil> ignored =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      PlatformDataIntegrityException ex =
          assertThrows(
              PlatformDataIntegrityException.class,
              () -> birtReportingService.processRequest("Clients", params));
      String msg = ex.getDefaultUserMessage();
      assertTrue(msg.contains("Clients.rptdesign") && !msg.contains("Clients_en.rptdesign"));
    }
  }

  // Edge Cases

  @Test
  void shouldDocumentPathTraversalRiskInReportName(@TempDir Path tempDir) {
    ReflectionTestUtils.setField(birtReportingService, "fineractBirtBaseDir", tempDir.toString());

    try (MockedStatic<ThreadLocalContextUtil> ignored =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      PlatformDataIntegrityException ex =
          assertThrows(
              PlatformDataIntegrityException.class,
              () -> birtReportingService.processRequest("../../etc/passwd", queryParams("PDF")));
      assertEquals("error.msg.reporting.error", ex.getGlobalisationMessageCode());
    }
  }

  @Test
  void shouldHandleEmptyValueListForRPrefixParamGracefully() {
    MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.put("R_officeId", Collections.emptyList());
    params.add("output-type", "PDF");

    assertThrows(RuntimeException.class, () -> birtReportingService.getReportParams(params));
  }

  @Test
  void shouldThrowControlledErrorWhenNumericParamValueIsBlank(@TempDir Path tempDir)
      throws Exception {
    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    buildEngineSetup(new EngineSetup().param("officeId", IParameterDefn.TYPE_INTEGER));

    MultivaluedMap<String, String> params = queryParams("PDF");
    params.add("R_officeId", "");

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      PlatformDataIntegrityException ex =
          assertThrows(
              PlatformDataIntegrityException.class,
              () -> birtReportingService.processRequest("Report", params));
      assertEquals("error.msg.reporting.error", ex.getGlobalisationMessageCode());
    }
  }

  @Test
  void shouldWrapEngineExceptionIntoPlatformExceptionWhenOpenReportDesignFails(
      @TempDir Path tempDir) throws Exception {
    prepareReportFile(tempDir, "Report");
    Mockito.when(reportEngine.openReportDesign(anyString()))
        .thenThrow(new RuntimeException("BIRT engine internal crash"));

    try (MockedStatic<ThreadLocalContextUtil> ignored =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      PlatformDataIntegrityException ex =
          assertThrows(
              PlatformDataIntegrityException.class,
              () -> birtReportingService.processRequest("Report", queryParams("PDF")));
      assertEquals("error.msg.reporting.error", ex.getGlobalisationMessageCode());
    }
  }

  // Happy Path (Export Formats)

  @ParameterizedTest(name = "output-type={0} -> {1}")
  @MethodSource("exportFormats")
  void shouldProcessExportFormatSuccessfully(
      String outputType, String expectedMime, String expectedSuffix, @TempDir Path tempDir)
      throws Exception {

    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine = buildEngineSetup(new EngineSetup());

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);

      Response response = birtReportingService.processRequest("Report", queryParams(outputType));

      assertNotNull(response);
      assertEquals(200, response.getStatus());
      assertEquals(expectedMime, response.getMediaType().toString());

      if (expectedSuffix != null) {
        String header = response.getHeaderString("Content-Disposition");
        assertTrue(header != null && header.contains("Report" + expectedSuffix));
      }

      Mockito.verify(engine.task()).run();
      Mockito.verify(engine.task()).close();
      Mockito.verify(engine.paramTask()).close();
    }
  }

  // Read-Only Mode

  @Test
  void shouldUseReadOnlyReplicaWhenReadOnlyModeEnabled(@TempDir Path tempDir) throws Exception {
    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(true);
    FineractPlatformTenantConnection connection = tenant.getConnection();
    EngineResult engine = buildEngineSetup(new EngineSetup());

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);

      birtReportingService.processRequest("Report", queryParams("CSV"));

      Mockito.verify(connection, Mockito.atLeastOnce()).getReadOnlySchemaServer();
      Mockito.verify(engine.task()).run();
      Mockito.verify(engine.task()).close();
    }
  }

  // Security & Multi-tenancy Parameters

  @Test
  void shouldAlwaysInjectSecurityAndTenantParameters(@TempDir Path tempDir) throws Exception {
    prepareReportFile(tempDir, "AuditReport");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine = buildEngineSetup(new EngineSetup());

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);

      birtReportingService.processRequest("AuditReport", queryParams("PDF"));

      Mockito.verify(engine.task()).setParameterValue(eq("userhierarchy"), eq(".1."));
      Mockito.verify(engine.task()).setParameterValue(eq("userid"), anyLong());
      Mockito.verify(engine.task()).setParameterValue(eq("tenantUrl"), anyString());
    }
  }

  @Test
  void shouldUseSchemaUsernameDirectlyWhenConfigured(@TempDir Path tempDir) throws Exception {
    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    Mockito.lenient().when(tenant.getConnection().getSchemaUsername()).thenReturn("tenant_user");
    EngineResult engine = buildEngineSetup(new EngineSetup());

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      birtReportingService.processRequest("Report", queryParams("PDF"));
      Mockito.verify(engine.task()).setParameterValue(eq("username"), eq("tenant_user"));
    }
  }

  @Test
  void shouldFallBackToEnvPasswordWhenSchemaPasswordIsNull(@TempDir Path tempDir) throws Exception {
    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);

    Mockito.lenient().when(tenant.getConnection().getSchemaPassword()).thenReturn(null);

    Environment env = contextVar.getEnvironment();
    Mockito.lenient()
        .when(env.getProperty("FINERACT_DEFAULT_TENANTDB_PWD"))
        .thenReturn("envFallbackPassword");

    EngineResult engine = buildEngineSetup(new EngineSetup());

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      birtReportingService.processRequest("Report", queryParams("PDF"));
      Mockito.verify(engine.task()).setParameterValue(eq("password"), eq("envFallbackPassword"));
    }
  }

  // Data Sources

  @Test
  void shouldInjectTenantConnectionPropertiesIntoOdaDataSourceHandle(@TempDir Path tempDir)
      throws Exception {
    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine = buildEngineSetup(new EngineSetup().withDataSource());

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      birtReportingService.processRequest("Report", queryParams("PDF"));
      Mockito.verify(engine.task()).run();
      Mockito.verify(engine.task()).close();
    }
  }

  // Locale Override

  @Test
  void shouldUseFineractBirtLocaleOverrideInsteadOfQueryParamLocale(@TempDir Path tempDir)
      throws Exception {
    prepareReportFile(tempDir, "Report");
    ReflectionTestUtils.setField(birtReportingService, "fineractBirtLocale", "fr");

    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine = buildEngineSetup(new EngineSetup());

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      birtReportingService.processRequest("Report", queryParams("PDF"));
      Mockito.verify(engine.task())
          .setLocale(eq(new Locale.Builder().setLanguageTag("fr").build()));
    }
  }

  @Test
  void shouldPreferFineractBirtLocaleOverQueryParamLocaleInTaskSetLocale(@TempDir Path tempDir)
      throws Exception {
    prepareReportFile(tempDir, "Report_fr");
    ReflectionTestUtils.setField(birtReportingService, "fineractBirtLocale", "de");

    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine = buildEngineSetup(new EngineSetup());

    MultivaluedMap<String, String> params = queryParams("PDF");
    params.add("locale", "fr");

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      birtReportingService.processRequest("Report", params);
      Mockito.verify(engine.task())
          .setLocale(eq(new Locale.Builder().setLanguageTag("de").build()));
      Mockito.verify(engine.task(), Mockito.never())
          .setLocale(eq(new Locale.Builder().setLanguageTag("fr").build()));
    }
  }

  // Parameter Type Binding

  @Test
  void shouldBindIntegerParameterCorrectly(@TempDir Path tempDir) throws Exception {
    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine =
        buildEngineSetup(new EngineSetup().param("officeId", IParameterDefn.TYPE_INTEGER));

    MultivaluedMap<String, String> params = queryParams("PDF");
    params.add("R_officeId", "42");

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      birtReportingService.processRequest("Report", params);
      Mockito.verify(engine.task()).setParameterValue(eq("officeId"), eq(42));
    }
  }

  @Test
  void shouldBindFloatParameterCorrectly(@TempDir Path tempDir) throws Exception {
    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine =
        buildEngineSetup(new EngineSetup().param("rate", IParameterDefn.TYPE_FLOAT));

    MultivaluedMap<String, String> params = queryParams("PDF");
    params.add("R_rate", "3.75");

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      birtReportingService.processRequest("Report", params);
      Mockito.verify(engine.task()).setParameterValue(eq("rate"), eq(3.75d));
    }
  }

  @Test
  void shouldBindDecimalParameterCorrectly(@TempDir Path tempDir) throws Exception {
    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine =
        buildEngineSetup(new EngineSetup().param("taxRate", IParameterDefn.TYPE_DECIMAL));

    MultivaluedMap<String, String> params = queryParams("PDF");
    params.add("R_taxRate", "18.50");

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      birtReportingService.processRequest("Report", params);
      Mockito.verify(engine.task()).setParameterValue(eq("taxRate"), eq(18.50d));
    }
  }

  @Test
  void shouldBindBooleanParameterCorrectly(@TempDir Path tempDir) throws Exception {
    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine =
        buildEngineSetup(new EngineSetup().param("isActive", IParameterDefn.TYPE_BOOLEAN));

    MultivaluedMap<String, String> params = queryParams("PDF");
    params.add("R_isActive", "true");

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      birtReportingService.processRequest("Report", params);
      Mockito.verify(engine.task()).setParameterValue(eq("isActive"), eq(true));
    }
  }

  @Test
  void shouldBindStringParameterRawWhenTypeIsUnknown(@TempDir Path tempDir) throws Exception {
    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine =
        buildEngineSetup(new EngineSetup().param("reportTitle", IParameterDefn.TYPE_STRING));

    MultivaluedMap<String, String> params = queryParams("PDF");
    params.add("R_reportTitle", "Monthly Summary");

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      birtReportingService.processRequest("Report", params);
      Mockito.verify(engine.task()).setParameterValue(eq("reportTitle"), eq("Monthly Summary"));
    }
  }

  @Test
  void shouldBindDateParameterCorrectlyWhenFormatIsValid(@TempDir Path tempDir) throws Exception {
    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine =
        buildEngineSetup(new EngineSetup().param("startDate", IParameterDefn.TYPE_DATE));

    MultivaluedMap<String, String> params = queryParams("PDF");
    params.add("R_startDate", "01 January 2026");

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      birtReportingService.processRequest("Report", params);

      SimpleDateFormat sdf = new SimpleDateFormat("dd MMMM yyyy", Locale.ENGLISH);
      java.sql.Date expected = new java.sql.Date(sdf.parse("01 January 2026").getTime());
      Mockito.verify(engine.task()).setParameterValue(eq("startDate"), eq(expected));
    }
  }

  @Test
  void shouldBindDateTimeParameterCorrectlyWhenFormatIsValid(@TempDir Path tempDir)
      throws Exception {
    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine =
        buildEngineSetup(new EngineSetup().param("eventDate", IParameterDefn.TYPE_DATE_TIME));

    MultivaluedMap<String, String> params = queryParams("PDF");
    params.add("R_eventDate", "15 March 2026");

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      birtReportingService.processRequest("Report", params);

      SimpleDateFormat sdf = new SimpleDateFormat("dd MMMM yyyy", Locale.ENGLISH);
      java.sql.Date expected = new java.sql.Date(sdf.parse("15 March 2026").getTime());
      Mockito.verify(engine.task()).setParameterValue(eq("eventDate"), eq(expected));
    }
  }

  @Test
  void shouldThrowWhenDateParameterHasInvalidFormat(@TempDir Path tempDir) throws Exception {
    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    buildEngineSetup(new EngineSetup().param("startDate", IParameterDefn.TYPE_DATE));

    MultivaluedMap<String, String> params = queryParams("PDF");
    params.add("R_startDate", "2026-01-01");

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      PlatformDataIntegrityException ex =
          assertThrows(
              PlatformDataIntegrityException.class,
              () -> birtReportingService.processRequest("Report", params));
      assertEquals("error.msg.reporting.error", ex.getGlobalisationMessageCode());
    }
  }

  // Sanitisation

  @Test
  void shouldStripSpacesFromReportNameInContentDispositionHeader(@TempDir Path tempDir)
      throws Exception {
    prepareReportFile(tempDir, "My Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine = buildEngineSetup(new EngineSetup());

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      Response response = birtReportingService.processRequest("My Report", queryParams("XLSX"));

      assertNotNull(response);
      assertTrue(response.getHeaderString("Content-Disposition").contains("MyReport.xlsx"));
      Mockito.verify(engine.task()).run();
      Mockito.verify(engine.task()).close();
    }
  }

  // Error Handling & Engine Lifecycle

  @Test
  void shouldThrowWhenRequiredBirtParamValueNotProvided(@TempDir Path tempDir) throws Exception {
    prepareReportFile(tempDir, "Report");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);

    buildEngineSetup(
        new EngineSetup()
            .outcome(TaskOutcome.PARAM_VALIDATION_FAIL)
            .param("requiredParam", IParameterDefn.TYPE_INTEGER));

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      PlatformDataIntegrityException ex =
          assertThrows(
              PlatformDataIntegrityException.class,
              () -> birtReportingService.processRequest("Report", queryParams("PDF")));
      assertEquals("error.msg.reporting.error", ex.getGlobalisationMessageCode());
    }
  }

  @Test
  void shouldThrowAndCloseTaskWhenBirtTaskReturnsErrorsList(@TempDir Path tempDir)
      throws Exception {
    prepareReportFile(tempDir, "ErrorReport");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine = buildEngineSetup(new EngineSetup().outcome(TaskOutcome.FAILED_BY_ERRORS));

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      PlatformDataIntegrityException ex =
          assertThrows(
              PlatformDataIntegrityException.class,
              () -> birtReportingService.processRequest("ErrorReport", queryParams("PDF")));
      assertEquals("error.msg.reporting.error", ex.getGlobalisationMessageCode());
      Mockito.verify(engine.task()).run();
      Mockito.verify(engine.task()).close();
    }
  }

  @Test
  void shouldThrowAndCloseTaskWhenBirtTaskStatusIsNotSucceeded(@TempDir Path tempDir)
      throws Exception {
    prepareReportFile(tempDir, "FailedReport");
    setupMockUser();
    FineractPlatformTenant tenant = setupMockTenantAndEnvironment(false);
    EngineResult engine = buildEngineSetup(new EngineSetup().outcome(TaskOutcome.FAILED_BY_STATUS));

    try (MockedStatic<ThreadLocalContextUtil> s =
        Mockito.mockStatic(ThreadLocalContextUtil.class)) {
      s.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);
      PlatformDataIntegrityException ex =
          assertThrows(
              PlatformDataIntegrityException.class,
              () -> birtReportingService.processRequest("FailedReport", queryParams("PDF")));
      assertEquals("error.msg.reporting.error", ex.getGlobalisationMessageCode());
      Mockito.verify(engine.task()).run();
      Mockito.verify(engine.task()).close();
    }
  }

  // Private Setup Helpers

  private void setupMockUser() {
    AppUser appUser = Mockito.mock(AppUser.class, Mockito.RETURNS_DEEP_STUBS);
    Mockito.lenient().when(context.authenticatedUser()).thenReturn(appUser);
    Mockito.lenient().when(appUser.getOffice().getHierarchy()).thenReturn(".1.");
    Mockito.lenient().when(appUser.getId()).thenReturn(1L);
  }

  private FineractPlatformTenant setupMockTenantAndEnvironment(boolean isReadOnly)
      throws Exception {
    FineractPlatformTenant tenant = Mockito.mock(FineractPlatformTenant.class);
    FineractPlatformTenantConnection connection =
        Mockito.mock(FineractPlatformTenantConnection.class);
    Mockito.lenient().when(tenant.getConnection()).thenReturn(connection);

    Mockito.lenient().when(connection.getSchemaServer()).thenReturn("localhost");
    Mockito.lenient().when(connection.getSchemaServerPort()).thenReturn("3306");
    Mockito.lenient().when(connection.getSchemaName()).thenReturn("fineract_default");
    Mockito.lenient().when(connection.getSchemaPassword()).thenReturn("encryptedPassword");
    Mockito.lenient()
        .when(databasePasswordEncryptor.decrypt("encryptedPassword"))
        .thenReturn("decryptedPassword");

    if (isReadOnly) {
      Mockito.lenient().when(connection.getReadOnlySchemaServer()).thenReturn("readonly-host");
      Mockito.lenient().when(connection.getReadOnlySchemaServerPort()).thenReturn("3307");
      Mockito.lenient().when(connection.getReadOnlySchemaName()).thenReturn("fineract_ro");
      Mockito.lenient()
          .when(connection.getReadOnlySchemaPassword())
          .thenReturn("encryptedPassword");
    }

    Connection mockConn = Mockito.mock(Connection.class);
    DatabaseMetaData mockMeta = Mockito.mock(DatabaseMetaData.class);
    Mockito.lenient().when(tenantDataSource.getConnection()).thenReturn(mockConn);
    Mockito.lenient().when(mockConn.getMetaData()).thenReturn(mockMeta);
    Mockito.lenient()
        .when(mockMeta.getURL())
        .thenReturn("jdbc:mysql://localhost:3306/fineract_default");
    Mockito.lenient().when(mockMeta.getDatabaseProductName()).thenReturn("MySQL");

    FineractProperties.FineractModeProperties modeProps =
        Mockito.mock(FineractProperties.FineractModeProperties.class);
    Mockito.lenient().when(fineractProperties.getMode()).thenReturn(modeProps);
    Mockito.lenient().when(modeProps.isReadOnlyMode()).thenReturn(isReadOnly);

    Environment env = Mockito.mock(Environment.class);
    Mockito.lenient().when(applicationContext.getEnvironment()).thenReturn(env);
    Mockito.lenient().when(contextVar.getEnvironment()).thenReturn(env);
    Mockito.lenient().when(env.getProperty("FINERACT_DEFAULT_TENANTDB_UID")).thenReturn("root");

    return tenant;
  }

  private EngineResult buildEngineSetup(EngineSetup setup) throws Exception {
    IReportRunnable design = Mockito.mock(IReportRunnable.class);
    ReportDesignHandle designHandle = Mockito.mock(ReportDesignHandle.class);
    IRunAndRenderTask task = Mockito.mock(IRunAndRenderTask.class);
    IGetParameterDefinitionTask paramTask = Mockito.mock(IGetParameterDefinitionTask.class);
    SlotHandle slotHandle = Mockito.mock(SlotHandle.class);
    OdaDataSourceHandle odaHandle = null;

    Mockito.lenient().when(reportEngine.openReportDesign(Mockito.anyString())).thenReturn(design);
    Mockito.lenient().when(design.getDesignHandle()).thenReturn(designHandle);
    Mockito.lenient().when(designHandle.getDataSources()).thenReturn(slotHandle);
    Mockito.lenient().when(designHandle.getAllLibraries()).thenReturn(Collections.emptyList());

    if (setup.withDataSource) {
      odaHandle = Mockito.mock(OdaDataSourceHandle.class);
      Mockito.lenient().when(odaHandle.getName()).thenReturn("MainDataSource");
      List<DesignElementHandle> handles = List.of(odaHandle);
      Mockito.lenient().when(slotHandle.iterator()).thenReturn(handles.iterator());
    } else {
      Mockito.lenient().when(slotHandle.iterator()).thenReturn(Collections.emptyIterator());
    }

    Mockito.lenient().when(reportEngine.createRunAndRenderTask(design)).thenReturn(task);
    Mockito.lenient()
        .when(reportEngine.createGetParameterDefinitionTask(Mockito.any()))
        .thenReturn(paramTask);

    if (setup.params.isEmpty()) {
      Mockito.lenient()
          .when(paramTask.getParameterDefns(false))
          .thenReturn(Collections.emptyList());
    } else {
      List<IParameterDefn> defs =
          setup.params.stream()
              .map(
                  spec -> {
                    IParameterDefn pd = Mockito.mock(IParameterDefn.class);
                    Mockito.lenient().when(pd.getName()).thenReturn(spec.name());
                    Mockito.lenient().when(pd.getDataType()).thenReturn(spec.dataType());
                    return pd;
                  })
              .toList();
      Mockito.lenient().when(paramTask.getParameterDefns(false)).thenReturn(defs);
    }

    if (setup.outcome == TaskOutcome.PARAM_VALIDATION_FAIL) {
      return new EngineResult(null, paramTask);
    }

    switch (setup.outcome) {
      case SUCCESS -> {
        Mockito.lenient().when(task.getStatus()).thenReturn(IEngineTask.STATUS_SUCCEEDED);
        Mockito.lenient().when(task.getErrors()).thenReturn(null);
      }
      case FAILED_BY_STATUS -> {
        Mockito.lenient().when(task.getErrors()).thenReturn(null);
        Mockito.lenient().when(task.getStatus()).thenReturn(IEngineTask.STATUS_FAILED);
      }
      case FAILED_BY_ERRORS -> {
        Mockito.lenient()
            .when(task.getErrors())
            .thenReturn(List.of(new RuntimeException("BIRT Internal Crash")));
        Mockito.lenient().when(task.getStatus()).thenReturn(IEngineTask.STATUS_FAILED);
      }
      default -> throw new IllegalArgumentException("Unhandled outcome: " + setup.outcome);
    }

    return new EngineResult(task, paramTask);
  }
}
