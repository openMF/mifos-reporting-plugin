/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection.toJdbcUrl;
import static org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection.toProtocol;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
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
import org.pentaho.reporting.engine.classic.core.ClassicEngineBoot;
import org.pentaho.reporting.engine.classic.core.CompoundDataFactory;
import org.pentaho.reporting.engine.classic.core.DataFactory;
import org.pentaho.reporting.engine.classic.core.DefaultReportEnvironment;
import org.pentaho.reporting.engine.classic.core.Element;
import org.pentaho.reporting.engine.classic.core.MasterReport;
import org.pentaho.reporting.engine.classic.core.Section;
import org.pentaho.reporting.engine.classic.core.SubReport;
import org.pentaho.reporting.engine.classic.core.modules.misc.datafactory.sql.DriverConnectionProvider;
import org.pentaho.reporting.engine.classic.core.modules.misc.datafactory.sql.SQLReportDataFactory;
import org.pentaho.reporting.engine.classic.core.modules.output.pageable.pdf.PdfReportUtil;
import org.pentaho.reporting.engine.classic.core.modules.output.table.csv.CSVReportUtil;
import org.pentaho.reporting.engine.classic.core.modules.output.table.html.HtmlReportUtil;
import org.pentaho.reporting.engine.classic.core.modules.output.table.xls.ExcelReportUtil;
import org.pentaho.reporting.engine.classic.core.modules.output.pageable.xml.XmlPageReportUtil;
import org.pentaho.reporting.engine.classic.core.parameters.ParameterDefinitionEntry;
import org.pentaho.reporting.libraries.resourceloader.Resource;
import org.pentaho.reporting.libraries.resourceloader.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
@ReportService(type = "Pentaho")
public class PentahoReportingProcessServiceImpl implements ReportingProcessService {

  private static final Logger logger =
          LoggerFactory.getLogger(PentahoReportingProcessServiceImpl.class);
  private final String mifosBaseDir = System.getProperty("user.home") + File.separator + ".mifosx";
  private final DatabasePasswordEncryptor databasePasswordEncryptor;
  private final PlatformSecurityContext context;
  private final DataSource tenantDataSource;

  // Componente inyectado encargado del bypass y procesamiento de los xml SUGEF nativos
  private final NativeReportStrategyComponent nativeReportStrategyComponent;

  @Value("${FINERACT_PENTAHO_REPORTS_PATH}")
  private String fineractPentahoBaseDir;

  @Value("${FINERACT_PENTAHO_REPORTS_LOCALE}")
  private String fineractPentahoLocale;

  @Autowired FineractProperties fineractProperties;
  @Autowired ApplicationContext applicationContext;

  @Autowired
  public PentahoReportingProcessServiceImpl(
          final PlatformSecurityContext context,
          final @Qualifier("hikariTenantDataSource") DataSource tenantDataSource,
          DatabasePasswordEncryptor databasePasswordEncryptor,
          NativeReportStrategyComponent nativeReportStrategyComponent) {
    ClassicEngineBoot.getInstance().start();
    this.tenantDataSource = tenantDataSource;
    this.context = context;
    this.databasePasswordEncryptor = databasePasswordEncryptor;
    this.nativeReportStrategyComponent = nativeReportStrategyComponent;
  }

  public List<SubReport> getSubReports(MasterReport masterReport) {
    List<SubReport> subReports = new ArrayList<>();
    collectSubReports(masterReport.getReportHeader(), subReports);
    collectSubReports(masterReport.getReportFooter(), subReports);
    collectSubReports(masterReport.getPageHeader(), subReports);
    collectSubReports(masterReport.getPageFooter(), subReports);
    collectSubReports(masterReport.getItemBand(), subReports);
    return subReports;
  }

  private void collectSubReports(Section section, List<SubReport> subReports) {
    if (section == null) {
      return;
    }
    for (int i = 0; i < section.getElementCount(); i++) {
      Element element = section.getElement(i);
      if (element instanceof SubReport subReport) {
        subReports.add(subReport);
        for (int j = 0; j < subReport.getElementCount(); j++) {
          if (subReport.getElement(j) instanceof Section subSection) {
            collectSubReports(subSection, subReports);
          }
        }
      }
    }
  }

  @Override
  public Response processRequest(
          final String reportName, final MultivaluedMap<String, String> queryParams) {

    // INTERCEPCIÓN COHERENTE: Si es un reporte nativo (Reporte44Xml o Reporte45Xml) delegamos al componente
    if (this.nativeReportStrategyComponent.isNativeReport(reportName)) {
      return this.nativeReportStrategyComponent.processNativeRequest(reportName, queryParams);
    }

    final var outputTypeParam = queryParams.getFirst("output-type");
    final var reportParams = getReportParams(queryParams);
    final var locale = ApiParameterHelper.extractLocale(queryParams);

    var outputType = "HTML";
    if (StringUtils.isNotBlank(outputTypeParam)) {
      outputType = outputTypeParam;
    }

    if ((!outputType.equalsIgnoreCase("HTML")
            && !outputType.equalsIgnoreCase("PDF")
            && !outputType.equalsIgnoreCase("XLS")
            && !outputType.equalsIgnoreCase("XLSX")
            && !outputType.equalsIgnoreCase("XML")
            && !outputType.equalsIgnoreCase("CSV"))) {
      throw new PlatformDataIntegrityException(
              "error.msg.invalid.outputType", "No matching Output Type: " + outputType);
    }

    // FLUJO PENTAHO TRADICIONAL (.PRPT)
    String reportPath;
    if (locale != null && !"en".equals(locale.toString().toLowerCase())) {
      reportPath = getReportPath() + reportName + "_" + locale.toString().toLowerCase() + ".prpt";
    } else {
      reportPath = getReportPath() + reportName + ".prpt";
    }
    logger.debug("Report path: {}", reportPath);

    final var manager = new ResourceManager();
    manager.registerDefaults();
    Resource res;

    try {
      res = manager.createDirectly(reportPath, MasterReport.class);
      final var masterReport = (MasterReport) res.getResource();

      CompoundDataFactory compoundDataFactory = (CompoundDataFactory) masterReport.getDataFactory();
      setConnectionDetail(compoundDataFactory.get(0));

      final var reportEnvironment = (DefaultReportEnvironment) masterReport.getReportEnvironment();

      if (fineractPentahoBaseDir != null) {
        Locale localeReport = new Locale.Builder().setLanguageTag(fineractPentahoLocale).build();
        reportEnvironment.setLocale(localeReport);
      } else if (locale != null) {
        reportEnvironment.setLocale(locale);
      }

      addParametersToReport(masterReport, reportParams);

      List<SubReport> subReports = getSubReports(masterReport);
      for (SubReport subReport : subReports) {
        CompoundDataFactory subReportCompoundDataFactory =
                (CompoundDataFactory) subReport.getDataFactory();
        setConnectionDetail(subReportCompoundDataFactory.get(0));
      }

      final var baos = new ByteArrayOutputStream();

      if ("PDF".equalsIgnoreCase(outputType)) {
        PdfReportUtil.createPDF(masterReport, baos);
        return Response.ok().entity(baos.toByteArray()).type("application/pdf").build();

      } else if ("XLS".equalsIgnoreCase(outputType)) {
        ExcelReportUtil.createXLS(masterReport, baos);
        return Response.ok()
                .entity(baos.toByteArray())
                .type("application/vnd.ms-excel")
                .header("Content-Disposition", "attachment;filename=" + reportName.replaceAll(" ", "") + ".xls")
                .build();

      } else if ("XLSX".equalsIgnoreCase(outputType)) {
        ExcelReportUtil.createXLSX(masterReport, baos);
        return Response.ok()
                .entity(baos.toByteArray())
                .type("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition", "attachment;filename=" + reportName.replaceAll(" ", "") + ".xlsx")
                .build();

      } else if ("CSV".equalsIgnoreCase(outputType)) {
        CSVReportUtil.createCSV(masterReport, baos, "UTF-8");
        return Response.ok()
                .entity(baos.toByteArray())
                .type("text/csv")
                .header("Content-Disposition", "attachment;filename=" + reportName.replaceAll(" ", "") + ".csv")
                .build();

      } else if ("HTML".equalsIgnoreCase(outputType)) {
        HtmlReportUtil.createStreamHTML(masterReport, baos);
        return Response.ok().entity(baos.toByteArray()).type("text/html").build();

      } else if ("XML".equalsIgnoreCase(outputType)) {
        XmlPageReportUtil.createXml(masterReport, baos);
        return Response.ok()
                .entity(baos.toByteArray())
                .type("application/xml")
                .header("Content-Disposition", "attachment;filename=" + reportName.replaceAll(" ", "") + ".xml")
                .build();

      } else {
        throw new PlatformDataIntegrityException(
                "error.msg.invalid.outputType", "No matching Output Type: " + outputType);
      }
    } catch (Throwable t) {
      logger.error("Pentaho failed", t);
      throw new PlatformDataIntegrityException(
              "error.msg.reporting.error", "Pentaho failed: " + t.getMessage());
    }
  }

  private void addParametersToReport(
          final MasterReport report, final Map<String, String> queryParams) {
    final var currentUser = this.context.authenticatedUser();
    try {
      final var rptParamValues = report.getParameterValues();
      final var paramsDefinition = report.getParameterDefinition();

      for (final ParameterDefinitionEntry paramDefEntry :
              paramsDefinition.getParameterDefinitions()) {
        final var paramName = paramDefEntry.getName();
        if ((!paramName.equals("tenantUrl")
                && (!paramName.equals("userhierarchy")
                && !paramName.equals("username")
                && (!paramName.equals("password") && !paramName.equals("userid"))))) {

          logger.debug("paramName: {}", paramName);

          final var pValue = queryParams.get(paramName);
          if (StringUtils.isBlank(pValue)) {
            throw new PlatformDataIntegrityException(
                    "error.msg.reporting.error", "Pentaho Parameter: " + paramName + " - not Provided");
          }

          final Class<?> clazz = paramDefEntry.getValueType();
          logger.debug("addParametersToReport({} : {} : {})", paramName, pValue, clazz.getCanonicalName());

          if (clazz.getCanonicalName().equalsIgnoreCase("java.lang.Integer")) {
            rptParamValues.put(paramName, Integer.parseInt(pValue));
          } else if (clazz.getCanonicalName().equalsIgnoreCase("java.lang.Long")) {
            rptParamValues.put(paramName, Long.parseLong(pValue));
          } else if (clazz.getCanonicalName().equalsIgnoreCase("java.sql.Date")) {
            String myDate = pValue.toString();
            SimpleDateFormat sdf = new SimpleDateFormat("dd MMMM yyyy", Locale.ENGLISH);
            Date date = sdf.parse(myDate);
            rptParamValues.put(paramName, new java.sql.Date(date.getTime()));
          } else {
            rptParamValues.put(paramName, pValue);
          }
        }
      }

      final var tenant = ThreadLocalContextUtil.getTenant();
      final var tenantConnection = tenant.getConnection();
      String protocol = toProtocol(this.tenantDataSource);
      Environment environment = applicationContext.getEnvironment(); // Corregida inyección duplicada de contextVar
      String tenantUrl =
              toJdbcUrl(
                      protocol,
                      tenantConnection.getSchemaServer(),
                      tenantConnection.getSchemaServerPort(),
                      tenantConnection.getSchemaName(),
                      tenantConnection.getSchemaConnectionParameters());

      final var userhierarchy = currentUser.getOffice().getHierarchy();
      logger.debug("db URL: {}      userhierarchy: {}", tenantUrl, userhierarchy);

      rptParamValues.put("userhierarchy", userhierarchy);
      rptParamValues.put("userid", currentUser.getId());
      rptParamValues.put("tenantUrl", tenantUrl.trim());

      if (StringUtils.isBlank(tenantConnection.getSchemaUsername())) {
        rptParamValues.put("username", environment.getProperty("FINERACT_DEFAULT_TENANTDB_UID"));
      } else {
        rptParamValues.put("username", tenantConnection.getSchemaUsername().trim());
      }

      if (StringUtils.isBlank(tenantConnection.getSchemaPassword())) {
        rptParamValues.put("password", environment.getProperty("FINERACT_DEFAULT_TENANTDB_PWD"));
      } else {
        rptParamValues.put(
                "password",
                databasePasswordEncryptor.decrypt(tenantConnection.getSchemaPassword()).trim());
      }

    } catch (Throwable t) {
      logger.error("error.msg.reporting.error:", t);
      throw new PlatformDataIntegrityException("error.msg.reporting.error", t.getMessage());
    }
  }

  @Override
  public Map<String, String> getReportParams(final MultivaluedMap<String, String> queryParams) {
    final Map<String, String> reportParams = new HashMap<>();
    final var keys = queryParams.keySet();
    for (final String k : keys) {
      if (k.startsWith("R_")) {
        reportParams.put(k.substring(2), queryParams.get(k).get(0));
      }
    }
    return reportParams;
  }

  private String getReportPath() {
    if (fineractPentahoBaseDir != null) {
      return this.fineractPentahoBaseDir + File.separator;
    }
    return this.mifosBaseDir + File.separator + "pentahoReports" + File.separator;
  }

  private void setConnectionDetail(DataFactory dataFactory) throws SQLException {
    final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
    final FineractPlatformTenantConnection tenantConnection = tenant.getConnection();

    if (dataFactory instanceof SQLReportDataFactory) {
      SQLReportDataFactory sqlReportDataFactory = (SQLReportDataFactory) dataFactory;
      final DriverConnectionProvider connectionProvider =
              (DriverConnectionProvider) sqlReportDataFactory.getConnectionProvider();

      Driver e = DriverManager.getDriver(getTenantUrl());
      connectionProvider.setDriver(e.getClass().getName());
      connectionProvider.setUrl(getTenantUrl());
      connectionProvider.setProperty("user", tenantConnection.getSchemaUsername());
      connectionProvider.setProperty(
              "password",
              databasePasswordEncryptor.decrypt(tenantConnection.getSchemaPassword()).trim());
      sqlReportDataFactory.setConnectionProvider(connectionProvider);
    }
  }

  private String getTenantUrl() {
    final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
    final FineractPlatformTenantConnection tenantConnection = tenant.getConnection();
    String protocol = toProtocol(tenantDataSource);
    String schemaServer = tenantConnection.getSchemaServer();
    String schemaPort = tenantConnection.getSchemaServerPort();
    String schemaName = tenantConnection.getSchemaName();
    String schemaUsername = tenantConnection.getSchemaUsername();
    String schemaPassword = tenantConnection.getSchemaPassword();
    String schemaConnectionParameters = tenantConnection.getSchemaConnectionParameters();

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
      schemaUsername =
              getPropertyValue(
                      tenantConnection.getReadOnlySchemaUsername(),
                      TenantConstants.PROPERTY_RO_SCHEMA_USERNAME,
                      schemaUsername);
      schemaPassword =
              getPropertyValue(
                      tenantConnection.getReadOnlySchemaPassword(),
                      TenantConstants.PROPERTY_RO_SCHEMA_PASSWORD,
                      schemaPassword);
      schemaConnectionParameters =
              getPropertyValue(
                      tenantConnection.getReadOnlySchemaConnectionParameters(),
                      TenantConstants.PROPERTY_RO_SCHEMA_CONNECTION_PARAMETERS,
                      schemaConnectionParameters);
    }
    return toJdbcUrl(protocol, schemaServer, schemaPort, schemaName, schemaConnectionParameters);
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
}