package org.apache.fineract.infrastructure.report.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.fineract.infrastructure.dataqueries.data.ReportExportType;
import org.apache.fineract.infrastructure.report.annotation.ReportService;
import org.eclipse.birt.core.exception.BirtException;
import org.eclipse.birt.core.framework.Platform;
import org.eclipse.birt.report.engine.api.EngineConfig;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportEngineFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@ReportService(type = "BIRT")
public class BirtReportingProcessServiceImpl implements ReportingProcessService {

  private static final Logger LOG = LoggerFactory.getLogger(BirtReportingProcessServiceImpl.class);
  private IReportEngine birtEngine;

  @Autowired private DataSource dataSource;

  @PostConstruct // finerat engine init only once
  public void init() {
    LOG.info("Initializing Eclipse BIRT Report Engine...");
    try {
      EngineConfig config = new EngineConfig();
      config.setProperty(
          "birt.custom.font.dir",
          "/usr/share/fonts/truetype"); // In docker, we ensure that TTF fonts are available here

      Platform.startup(config);
      IReportEngineFactory factory =
          (IReportEngineFactory)
              Platform.createFactoryObject(IReportEngineFactory.EXTENSION_REPORT_ENGINE_FACTORY);
      birtEngine = factory.createReportEngine(config);

      LOG.info("Eclipse BIRT Report Engine is running.");

    } catch (BirtException e) {
      LOG.error("Error initializing BIRT engine", e);
    }
  }

  @PreDestroy
  public void destroy() {
    LOG.info("Shutting down Eclipse BIRT Report Engine...");
    if (birtEngine != null) {
      birtEngine.destroy();
    }
    Platform.shutdown();
  }

  @Override
  public Response processRequest(String reportName, MultivaluedMap<String, String> queryParams) {
    LOG.info("Processing BIRT report request for: {}", reportName);

    try {
      // we search for the report design file in the "birtReports"
      String baseDir = System.getenv("FINERACT_BIRT_REPORTS_PATH");
      if (baseDir == null || baseDir.isEmpty()) {
        baseDir = "/app/birtReports"; // docker path
      }
      String reportPath = baseDir + java.io.File.separator + reportName + ".rptdesign"; // abs path
      java.io.File reportFile = new java.io.File(reportPath);

      if (!reportFile.exists()) {
        LOG.error("Report design file not found: {}", reportPath);
        return Response.status(Response.Status.NOT_FOUND).entity("Report not found").build();
      }

      // open design
      org.eclipse.birt.report.engine.api.IReportRunnable design =
          birtEngine.openReportDesign(reportPath);

      // create task
      org.eclipse.birt.report.engine.api.IRunAndRenderTask task =
          birtEngine.createRunAndRenderTask(design);

      // we detect the locale from the query parameters
      String localeStr = queryParams.getFirst("locale");
      if (localeStr != null && !localeStr.isEmpty()) {
        // we support both "en" and "en_US" formats
        String[] parts = localeStr.split("_");
        java.util.Locale locale =
            (parts.length == 2)
                ? java.util.Locale.of(parts[0], parts[1])
                : java.util.Locale.of(localeStr);
        task.setLocale(locale);
        LOG.info("Applied locale: {}", locale);
      } else {
        task.setLocale(java.util.Locale.getDefault());
      }

      // we use parameters
      Map<String, String> reportParams = getReportParams(queryParams);
      for (Map.Entry<String, String> entry : reportParams.entrySet()) {
        String paramName = entry.getKey();
        String paramValue = entry.getValue();

        // we attempt to parse the parameter value as an integer
        try {
          Integer intValue = Integer.parseInt(paramValue);
          task.setParameterValue(paramName, intValue);
        } catch (NumberFormatException e) {
          // if parsing fails, we treat it as a string
          task.setParameterValue(paramName, paramValue);
        }
      }

      // we detect the format from URL, default to PDF
      String exportType = queryParams.getFirst("exportType");
      if (exportType == null || exportType.isEmpty()) {
        exportType = "pdf";
      }
      exportType = exportType.toLowerCase();

      // we support pdf, csv, xls, xlsx, html
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      org.eclipse.birt.report.engine.api.RenderOption renderOptions =
          new org.eclipse.birt.report.engine.api.RenderOption();
      renderOptions.setOutputStream(out);

      String contentType;
      String fileExtension;

      switch (exportType) {
        case "csv":
          renderOptions.setOutputFormat("csv");
          contentType = "text/csv";
          fileExtension = ".csv";
          break;
        case "xls": 
        // we use the new excel format
        case "xlsx":
          renderOptions.setOutputFormat("xlsx");
          contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
          fileExtension = ".xlsx";
          break;
        case "html":
          renderOptions.setOutputFormat("html");
          contentType = "text/html";
          fileExtension = ".html";
          break;
        case "pdf":
        default:
          // we use specific PDF render to avoid issues
          renderOptions = new org.eclipse.birt.report.engine.api.PDFRenderOption(renderOptions);
          renderOptions.setOutputFormat("pdf");
          contentType = "application/pdf";
          fileExtension = ".pdf";
          break;
      }

      task.setRenderOption(renderOptions);

      try (Connection springConnection = dataSource.getConnection()) {

        // native connection
        Connection nativeConnection = springConnection.unwrap(Connection.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> appContext = task.getAppContext();

        // we pass the native JDBC connection to BIRT
        appContext.put("OdaJDBCDriverPassInConnection", nativeConnection);
        appContext.put("OdaJDBCDriverPassInConnectionCloseAfterUse", false);

        task.run();

        // if fineract fail in silent, we log the errors from BIRT engine
        for (Object error : task.getErrors()) {
          LOG.error("BIRT'S HIDDEN ERROR: {}", error.toString());
        }

      } finally {
        task.close();
      }

      // we return the report as a response via navigator
      byte[] reportBytes = out.toByteArray();
      return Response.ok(reportBytes)
          .header(
              "Content-Disposition", "attachment; filename=\"" + reportName + fileExtension + "\"")
          .header("Content-Type", contentType)
          .build();

    } catch (Exception e) {
      LOG.error("Error rendering BIRT report", e);
      return Response.serverError().entity("Error generating report: " + e.getMessage()).build();
    }
  }

  @Override
  public List<ReportExportType> getAvailableExportTargets() {
    // we said what we support pdf, csv, xls, xlsx, html
    return List.of(
        new ReportExportType("PDF", "pdf"),
        new ReportExportType("XLS", "xls"),
        new ReportExportType("XLSX", "xlsx"),
        new ReportExportType("CSV", "csv"),
        new ReportExportType("HTML", "html"));
  }

  @Override
  public Map<String, String> getReportParams(final MultivaluedMap<String, String> queryParams) {
    // from pentaho, I add the check of null value to avoid null pointer exception
    final Map<String, String> reportParams = new HashMap<>();
    final var keys = queryParams.keySet();
    String pKey;
    String pValue;

    for (final String k : keys) {
      if (k.startsWith("R_")) {
        pKey = k.substring(2); // Remove "R_" prefix
        pValue = queryParams.get(k).get(0);
        if (pValue != null) { // Avoid null pointer
          reportParams.put(pKey, pValue);
        }
      }
    }
    return reportParams;
  }
}
