/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection.toJdbcUrl;
import static org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection.toProtocol;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.api.ApiParameterHelper;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabasePasswordEncryptor;
import org.apache.fineract.infrastructure.dataqueries.data.ReportExportType;
import org.apache.fineract.infrastructure.report.annotation.ReportService;
import org.apache.fineract.infrastructure.security.constants.TenantConstants;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.eclipse.birt.report.engine.api.EXCELRenderOption;
import org.eclipse.birt.report.engine.api.HTMLRenderOption;
import org.eclipse.birt.report.engine.api.IEngineTask;
import org.eclipse.birt.report.engine.api.IGetParameterDefinitionTask;
import org.eclipse.birt.report.engine.api.IPDFRenderOption;
import org.eclipse.birt.report.engine.api.IParameterDefn;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.eclipse.birt.report.engine.api.PDFRenderOption;
import org.eclipse.birt.report.engine.api.RenderOption;
import org.eclipse.birt.report.model.api.DesignElementHandle;
import org.eclipse.birt.report.model.api.LibraryHandle;
import org.eclipse.birt.report.model.api.OdaDataSourceHandle;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.eclipse.birt.report.model.api.SlotHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
@ReportService(type = "BIRT")
public class BirtReportingProcessServiceImpl implements ReportingProcessService {

  private static final Logger logger = LoggerFactory.getLogger(ReportingProcessService.class);

  private final String mifosBaseDir = System.getProperty("user.home") + File.separator + ".mifosx";
  private final DatabasePasswordEncryptor databasePasswordEncryptor;

  @Value("${FINERACT_BIRT_REPORTS_PATH:}")
  private String fineractBirtBaseDir;

  @Value("${FINERACT_BIRT_REPORTS_LOCALE:}")
  private String fineractBirtLocale;

  private final IReportEngine reportEngine;
  private final DataSource tenantDataSource;

  private final FineractProperties fineractProperties;

  private final ApplicationContext applicationContext;
  private final PlatformSecurityContext context;
  private final ApplicationContext contextVar;

  @Autowired
  public BirtReportingProcessServiceImpl(
      final PlatformSecurityContext context,
      final IReportEngine reportEngine,
      final @Qualifier("hikariTenantDataSource") DataSource tenantDataSource,
      DatabasePasswordEncryptor databasePasswordEncryptor,
      FineractProperties fineractProperties,
      ApplicationContext applicationContext,
      ApplicationContext contextVar) {
    this.reportEngine = reportEngine;
    this.tenantDataSource = tenantDataSource;
    this.databasePasswordEncryptor = databasePasswordEncryptor;
    this.fineractProperties = fineractProperties;
    this.context = context;
    this.applicationContext = applicationContext;
    this.contextVar = contextVar;
  }

  private void updateSubReportDataSources(ReportDesignHandle designHandle) {
    List<LibraryHandle> libraries = designHandle.getAllLibraries();
    logger.debug(
        "updateSubReportDataSources() called. Library count: {}",
        libraries != null ? libraries.size() : 0);
    if (libraries != null) {
      for (LibraryHandle library : libraries) {
        setConnectionDetail(library);
        // Recursively process nested libraries
        updateNestedLibraries(library);
      }
    }
  }

  private void updateNestedLibraries(LibraryHandle libraryHandle) {
    List<LibraryHandle> nestedLibraries = libraryHandle.getAllLibraries();
    logger.debug(
        "updateNestedLibraries() called. Nested library count: {}",
        nestedLibraries != null ? nestedLibraries.size() : 0);
    if (nestedLibraries != null) {
      for (LibraryHandle nested : nestedLibraries) {
        setConnectionDetail(nested);
        updateNestedLibraries(nested);
      }
    }
  }

  @Override
  public Response processRequest(
      final String reportName, final MultivaluedMap<String, String> queryParams) {
    final var outputTypeParam = queryParams.getFirst("output-type");
    final var reportParams = getReportParams(queryParams);
    final var locale = ApiParameterHelper.extractLocale(queryParams);
    final var language = "en";

    var outputType = "HTML";
    if (StringUtils.isNotBlank(outputTypeParam)) {
      outputType = outputTypeParam;
    }

    if ((!outputType.equalsIgnoreCase("HTML")
        && !outputType.equalsIgnoreCase("PDF")
        && !outputType.equalsIgnoreCase("XLS")
        && !outputType.equalsIgnoreCase("XLSX")
        && !outputType.equalsIgnoreCase("CSV"))) {
      throw new PlatformDataIntegrityException(
          "error.msg.invalid.outputType", "No matching Output Type: " + outputType);
    }
    logger.info(
        "Processing BIRT report: name='{}', outputType='{}', locale='{}'",
        reportName,
        outputType,
        locale);

    String reportPath;
    logger.debug("locale {}", locale);
    logger.debug("language {}", language);
    if (locale != null && !"en".equalsIgnoreCase(locale.toString())) {
      reportPath =
          getReportPath() + reportName + "_" + locale.toString().toLowerCase() + ".rptdesign";
    } else {
      reportPath = getReportPath() + reportName + ".rptdesign";
    }
    logger.debug("Report path: {}", reportPath);

    // load report definition
    IReportRunnable design;

    try {
      logger.info("Attempting to load report design from path: {}", reportPath);
      if (!new File(reportPath).exists()) {
        logger.error("Report design file not found at path: {}", reportPath);
        throw new PlatformDataIntegrityException(
            "error.msg.reporting.error", "Report file not found: " + reportPath);
      }
      design = reportEngine.openReportDesign(reportPath);
      logger.info("Report design loaded successfully: '{}'", reportPath);
      final var designHandle = (ReportDesignHandle) design.getDesignHandle();

      // Override Data Connection with tenant details
      setConnectionDetail(designHandle);
      logger.debug("Main report datasource connection details updated");

      // Update subreport data sources
      updateSubReportDataSources(designHandle);
      logger.debug("Subreport datasource connection details updated");

      // Set Locale for the report
      final var task = reportEngine.createRunAndRenderTask(design);

      // Force fast-failure on major errors
      task.setErrorHandlingOption(IEngineTask.CANCEL_ON_ERROR);
      logger.debug("BIRT task created, error handling set to CANCEL_ON_ERROR");

      try {
        if (StringUtils.isNotBlank(fineractBirtLocale)) {
          Locale localeReport = new Locale.Builder().setLanguageTag(fineractBirtLocale).build();
          task.setLocale(localeReport);
        } else if (locale != null) {
          task.setLocale(locale);
        }

        addParametersToReport(task, reportParams);
        logger.debug("Parameters bound to task successfully for report '{}'", reportName);

        final var baos = new ByteArrayOutputStream();

        if ("PDF".equalsIgnoreCase(outputType)) {
          PDFRenderOption pdfOptions = new PDFRenderOption();
          pdfOptions.setOutputFormat("pdf");
          pdfOptions.setOption(IPDFRenderOption.PAGE_OVERFLOW, IPDFRenderOption.FIT_TO_PAGE_SIZE);
          pdfOptions.setOutputStream(baos);
          task.setRenderOption(pdfOptions);
          task.run();
          logger.debug(
              "task.run() completed for report '{}', outputType='{}'", reportName, outputType);
          verifyTaskSuccess(task, reportName);
          logger.info(
              "Report '{}' generated successfully. Output size: {} bytes, type: '{}'",
              reportName,
              baos.size(),
              outputType);
          return Response.ok().entity(baos.toByteArray()).type("application/pdf").build();

        } else if ("XLS".equalsIgnoreCase(outputType)) {
          EXCELRenderOption excelOptions = new EXCELRenderOption();
          excelOptions.setOutputFormat("xls");
          excelOptions.setOutputStream(baos);
          task.setRenderOption(excelOptions);
          task.run();
          logger.debug(
              "task.run() completed for report '{}', outputType='{}'", reportName, outputType);
          verifyTaskSuccess(task, reportName);
          logger.info(
              "Report '{}' generated successfully. Output size: {} bytes, type: '{}'",
              reportName,
              baos.size(),
              outputType);
          return Response.ok()
              .entity(baos.toByteArray())
              .type("application/vnd.ms-excel")
              .header(
                  "Content-Disposition",
                  "attachment;filename=" + reportName.replaceAll(" ", "") + ".xls")
              .build();

        } else if ("XLSX".equalsIgnoreCase(outputType)) {
          EXCELRenderOption excelOptions = new EXCELRenderOption();
          excelOptions.setOutputFormat("xlsx");
          excelOptions.setOutputStream(baos);
          task.setRenderOption(excelOptions);
          task.run();
          logger.debug(
              "task.run() completed for report '{}', outputType='{}'", reportName, outputType);
          verifyTaskSuccess(task, reportName);
          logger.info(
              "Report '{}' generated successfully. Output size: {} bytes, type: '{}'",
              reportName,
              baos.size(),
              outputType);
          return Response.ok()
              .entity(baos.toByteArray())
              .type("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
              .header(
                  "Content-Disposition",
                  "attachment;filename=" + reportName.replaceAll(" ", "") + ".xlsx")
              .build();

        } else if ("CSV".equalsIgnoreCase(outputType)) {
          RenderOption csvOptions = new RenderOption();
          csvOptions.setOutputFormat("csv");
          csvOptions.setOutputStream(baos);
          task.setRenderOption(csvOptions);
          task.run();
          logger.debug(
              "task.run() completed for report '{}', outputType='{}'", reportName, outputType);
          verifyTaskSuccess(task, reportName);
          logger.info(
              "Report '{}' generated successfully. Output size: {} bytes, type: '{}'",
              reportName,
              baos.size(),
              outputType);
          return Response.ok()
              .entity(baos.toByteArray())
              .type("text/csv")
              .header(
                  "Content-Disposition",
                  "attachment;filename=" + reportName.replaceAll(" ", "") + ".csv")
              .build();

        } else if ("HTML".equalsIgnoreCase(outputType)) {
          HTMLRenderOption htmlOptions = new HTMLRenderOption();
          htmlOptions.setOutputFormat("html");
          htmlOptions.setEmbeddable(true);
          htmlOptions.setOutputStream(baos);
          task.setRenderOption(htmlOptions);
          task.run();
          logger.debug(
              "task.run() completed for report '{}', outputType='{}'", reportName, outputType);
          verifyTaskSuccess(task, reportName);
          logger.info(
              "Report '{}' generated successfully. Output size: {} bytes, type: '{}'",
              reportName,
              baos.size(),
              outputType);
          return Response.ok().entity(baos.toByteArray()).type("text/html").build();

        } else {
          throw new PlatformDataIntegrityException(
              "error.msg.invalid.outputType", "No matching Output Type: " + outputType);
        }
      } finally {
        task.close();
      }
    } catch (Exception e) {
      logger.error("error.msg.reporting.error:", e);
      throw new PlatformDataIntegrityException("error.msg.reporting.error", e.getMessage());
    }
  }

  private void addParametersToReport(
      final IRunAndRenderTask task, final Map<String, String> queryParams) {
    final var currentUser = this.context.authenticatedUser();
    logger.debug("addParametersToReport() called for report, user='{}'", currentUser.getUsername());
    try {
      final IGetParameterDefinitionTask paramTask =
          reportEngine.createGetParameterDefinitionTask(task.getReportRunnable());
      logger.debug("Parameter definition task created");
      try {
        for (final Object paramDefObj : paramTask.getParameterDefns(false)) {
          final IParameterDefn paramDefEntry = (IParameterDefn) paramDefObj;
          final var paramName = paramDefEntry.getName();

          logger.debug("paramName: {}", paramName);

          // Skip parameters that are injected server-side after this loop
          if (!paramName.equals("tenantUrl")
              && !paramName.equals("userhierarchy")
              && !paramName.equals("username")
              && !paramName.equals("password")
              && !paramName.equals("userid")) {

            final var pValue = queryParams.get(paramName);

            if (StringUtils.isBlank(pValue)) {
              throw new PlatformDataIntegrityException(
                  "error.msg.reporting.error", "BIRT Parameter: " + paramName + " - not Provided");
            }

            final int dataType = paramDefEntry.getDataType();
            logger.debug("addParametersToReport({} : {} : {})", paramName, pValue, dataType);

            if (dataType == IParameterDefn.TYPE_INTEGER) {
              task.setParameterValue(paramName, Integer.parseInt(pValue));
            } else if (dataType == IParameterDefn.TYPE_FLOAT
                || dataType == IParameterDefn.TYPE_DECIMAL) {
              task.setParameterValue(paramName, Double.parseDouble(pValue));
            } else if (dataType == IParameterDefn.TYPE_DATE
                || dataType == IParameterDefn.TYPE_DATE_TIME) {
              logger.debug("ParamName: {}", paramName);
              logger.debug("ParamValue: {}", pValue);
              SimpleDateFormat sdf = new SimpleDateFormat("dd MMMM yyyy", Locale.ENGLISH);
              Date date = sdf.parse(pValue);
              long millis = date.getTime();
              java.sql.Date mySQLDate = new java.sql.Date(millis);
              task.setParameterValue(paramName, mySQLDate);
              // Logging the parsed date value for debugging
              logger.debug("Date parameter '{}' parsed and set to: {}", paramName, mySQLDate);
            } else if (dataType == IParameterDefn.TYPE_BOOLEAN) {
              task.setParameterValue(paramName, Boolean.parseBoolean(pValue));
            } else {
              logger.debug("ParamName Unknown: {}", paramName);
              logger.debug("ParamValue Unknown: {}", pValue);
              task.setParameterValue(paramName, pValue);
            }
          }
        }
      } finally {
        paramTask.close();
      }

      // Context parameters for multitenant reporting
      final var tenant = ThreadLocalContextUtil.getTenant();
      final var tenantConnection = tenant.getConnection();
      String protocol = toProtocol(this.tenantDataSource);
      Environment environment = contextVar.getEnvironment();
      String tenantUrl =
          toJdbcUrl(
              protocol,
              tenantConnection.getSchemaServer(),
              tenantConnection.getSchemaServerPort(),
              tenantConnection.getSchemaName(),
              tenantConnection.getSchemaConnectionParameters());
      logger.debug("Tenant JDBC URL resolved: '{}'", tenantUrl);

      final var userhierarchy = currentUser.getOffice().getHierarchy();
      logger.debug("userhierarchy {}", userhierarchy);

      task.setParameterValue("userhierarchy", userhierarchy);

      final var userid = currentUser.getId();
      task.setParameterValue("userid", userid);

      task.setParameterValue("tenantUrl", tenantUrl.trim());

      String username;
      if (tenantConnection.getSchemaUsername() == null
          || tenantConnection.getSchemaUsername().isEmpty()) {
        username = environment.getProperty("FINERACT_DEFAULT_TENANTDB_UID");
      } else {
        username = tenantConnection.getSchemaUsername().trim();
      }
      task.setParameterValue("username", username);

      String password;
      if (tenantConnection.getSchemaPassword() == null
          || tenantConnection.getSchemaPassword().isEmpty()) {
        password = environment.getProperty("FINERACT_DEFAULT_TENANTDB_PWD");
      } else {
        password = databasePasswordEncryptor.decrypt(tenantConnection.getSchemaPassword()).trim();
      }
      task.setParameterValue("password", password);

    } catch (Exception e) {
      logger.error("error.msg.reporting.error:", e);
      throw new PlatformDataIntegrityException("error.msg.reporting.error", e.getMessage());
    }
  }

  @Override
  public Map<String, String> getReportParams(final MultivaluedMap<String, String> queryParams) {
    final Map<String, String> reportParams = new HashMap<>();
    final var keys = queryParams.keySet();
    String pKey;
    String pValue;
    for (final String k : keys) {
      if (k.startsWith("R_")) {
        pKey = k.substring(2);
        pValue = queryParams.get(k).get(0);
        reportParams.put(pKey, pValue);
      }
    }
    return reportParams;
  }

  private String getReportPath() {
    if (StringUtils.isNotBlank(fineractBirtBaseDir)) {
      return this.fineractBirtBaseDir.endsWith(File.separator)
          ? this.fineractBirtBaseDir
          : this.fineractBirtBaseDir + File.separator;
    }
    return this.mifosBaseDir + File.separator + "birtReports" + File.separator;
  }

  private void setConnectionDetail(ReportDesignHandle designHandle) {
    setConnectionDetailOnDataSources(designHandle.getDataSources());
  }

  private void setConnectionDetail(LibraryHandle libraryHandle) {
    setConnectionDetailOnDataSources(libraryHandle.getDataSources());
  }

  private void setConnectionDetailOnDataSources(SlotHandle dataSources) {
    logger.debug("setConnectionDetailOnDataSources() called");
    final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
    final FineractPlatformTenantConnection tenantConnection = tenant.getConnection();

    Iterator<DesignElementHandle> iterator = dataSources.iterator();

    String url = getTenantUrl();
    Environment environment = contextVar.getEnvironment();

    String user;
    if (tenantConnection.getSchemaUsername() == null
        || tenantConnection.getSchemaUsername().isEmpty()) {
      user = environment.getProperty("FINERACT_DEFAULT_TENANTDB_UID");
    } else {
      user = tenantConnection.getSchemaUsername().trim();
    }

    String password;
    if (tenantConnection.getSchemaPassword() == null
        || tenantConnection.getSchemaPassword().isEmpty()) {
      password = environment.getProperty("FINERACT_DEFAULT_TENANTDB_PWD");
    } else {
      password = databasePasswordEncryptor.decrypt(tenantConnection.getSchemaPassword()).trim();
    }

    while (iterator.hasNext()) {
      Object obj = iterator.next();
      if (obj instanceof OdaDataSourceHandle dataSource) {
        try {
          dataSource.setProperty("odaURL", url);
          dataSource.setProperty("odaUser", user);
          dataSource.setProperty("odaPassword", password);
          logger.debug("Updated DataSource: {}", dataSource.getName());
        } catch (Exception e) {
          logger.error("Failed to update DataSource: " + dataSource.getName(), e);
        }
      }
    }
  }

  private String getTenantUrl() {
    final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
    final FineractPlatformTenantConnection tenantConnection = tenant.getConnection();
    String protocol = toProtocol(tenantDataSource);
    // Default properties for Writing
    String schemaServer = tenantConnection.getSchemaServer();
    String schemaPort = tenantConnection.getSchemaServerPort();
    String schemaName = tenantConnection.getSchemaName();
    String schemaConnectionParameters = tenantConnection.getSchemaConnectionParameters();
    // Properties to ReadOnly case
    if (fineractProperties.getMode().isReadOnlyMode()) {
      schemaServer =
          getPropertyValue(
              tenantConnection.getReadOnlySchemaServer(),
              TenantConstants.PROPERTY_RO_SCHEMA_SERVER_NAME,
              schemaServer);
      schemaPort =
          getPropertyValue(
              tenantConnection.getReadOnlySchemaServerPort(),
              TenantConstants.PROPERTY_RO_SCHEMA_SERVER_PORT,
              schemaPort);
      schemaName =
          getPropertyValue(
              tenantConnection.getReadOnlySchemaName(),
              TenantConstants.PROPERTY_RO_SCHEMA_SCHEMA_NAME,
              schemaName);
      schemaConnectionParameters =
          getPropertyValue(
              tenantConnection.getReadOnlySchemaConnectionParameters(),
              TenantConstants.PROPERTY_RO_SCHEMA_CONNECTION_PARAMETERS,
              schemaConnectionParameters);
    }
    String jdbcUrl =
        toJdbcUrl(protocol, schemaServer, schemaPort, schemaName, schemaConnectionParameters);
    logger.debug("{}", jdbcUrl);

    return jdbcUrl;
  }

  private String getPropertyValue(
      final String baseValue, final String propertyName, final String defaultValue) {
    if (null != baseValue) {
      return baseValue;
    }
    if (applicationContext == null) {
      return defaultValue;
    }
    return applicationContext.getEnvironment().getProperty(propertyName, defaultValue);
  }

  @Override
  public List<ReportExportType> getAvailableExportTargets() {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  private void verifyTaskSuccess(final IRunAndRenderTask task, final String reportName) {
    logger.debug("verifyTaskSuccess() called for report '{}'", reportName);
    final List<?> taskErrors = task.getErrors();

    if (taskErrors != null && !taskErrors.isEmpty()) {
      // Log all errors for debugging
      for (Object error : taskErrors) {
        if (error instanceof Throwable throwable) {
          logger.error(
              "BIRT internal error during report '{}': {}",
              reportName,
              throwable.getMessage(),
              throwable);
        } else {
          logger.error("BIRT internal error during report '{}': {}", reportName, error);
        }
      }

      String firstErrorMsg =
          taskErrors.get(0) instanceof Throwable
              ? ((Throwable) taskErrors.get(0)).getMessage()
              : taskErrors.get(0).toString();

      throw new PlatformDataIntegrityException(
          "error.msg.reporting.error",
          "Report generation completed with internal errors for: "
              + reportName
              + ". Starting of error: "
              + firstErrorMsg);
    }

    final int taskStatus = task.getStatus();
    if (taskStatus != IEngineTask.STATUS_SUCCEEDED) {
      logger.error("BIRT task did not succeed for report '{}'. Status: {}", reportName, taskStatus);
      throw new PlatformDataIntegrityException(
          "error.msg.reporting.error", "Report generation failed. Task status: " + taskStatus);
    }
  }
}
