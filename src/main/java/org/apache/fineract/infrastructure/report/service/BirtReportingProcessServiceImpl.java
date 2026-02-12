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

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.dataqueries.data.ReportExportType;
import org.apache.fineract.infrastructure.report.annotation.ReportService;
import org.eclipse.birt.report.engine.api.HTMLRenderOption;
import org.eclipse.birt.report.engine.api.IPDFRenderOption;
import org.eclipse.birt.report.engine.api.IRenderOption;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.eclipse.birt.report.engine.api.PDFRenderOption;
import org.eclipse.birt.report.engine.api.RenderOption;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@ReportService(type = "BIRT")
public class BirtReportingProcessServiceImpl implements ReportingProcessService {

  private final IReportEngine reportEngine;
  private final DataSource tenantDataSource;

  @Value("${FINERACT_BIRT_REPORTS_PATH:}")
  private String birtReportsPath;

  public BirtReportingProcessServiceImpl(
      final IReportEngine reportEngine,
      @Qualifier("hikariTenantDataSource") final DataSource tenantDataSource) {
    this.reportEngine = reportEngine;
    this.tenantDataSource = tenantDataSource;
  }

  @Override
  public Response processRequest(String reportName, MultivaluedMap<String, String> queryParams) {
    String outputType = queryParams.getFirst("output-type");
    if (StringUtils.isBlank(outputType)) {
      outputType = "HTML";
    }

    // Resolve Report File Path
    String reportFilePath = getReportPath() + reportName + ".rptdesign";
    log.info("Processing BIRT Report: {}", reportFilePath);

    try {
      // Open the Report Design
      IReportRunnable design = reportEngine.openReportDesign(reportFilePath);

      // Create the Task
      IRunAndRenderTask task = reportEngine.createRunAndRenderTask(design);

      // Inject Database Connection (For Multi-Tenancy)
      // This allows BIRT to reuse the Fineract DataSource connection
      task.getAppContext().put("OdaJDBCDriverPassInConnection", tenantDataSource.getConnection());

      // Extract and Set Parameters
      Map<String, Object> parameters = getReportParamsObject(queryParams);
      task.setParameterValues(parameters);

      // Configure Output
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      IRenderOption options = new RenderOption();
      options.setOutputStream(baos);

      if ("PDF".equalsIgnoreCase(outputType)) {
        PDFRenderOption pdfOptions = new PDFRenderOption();
        pdfOptions.setOutputFormat("pdf");
        pdfOptions.setOutputStream(baos);
        pdfOptions.setOption(IPDFRenderOption.PAGE_OVERFLOW, IPDFRenderOption.FIT_TO_PAGE_SIZE);
        task.setRenderOption(pdfOptions);
      } else if ("HTML".equalsIgnoreCase(outputType)) {
        HTMLRenderOption htmlOptions = new HTMLRenderOption();
        htmlOptions.setOutputFormat("html");
        htmlOptions.setOutputStream(baos);
        htmlOptions.setEmbeddable(true);
        task.setRenderOption(htmlOptions);
      } else {
        throw new PlatformDataIntegrityException(
            "error.msg.invalid.outputType", "BIRT Output Type not supported: " + outputType);
      }

      // Run
      task.run();
      task.close();

      // Response
      String mimeType = "PDF".equalsIgnoreCase(outputType) ? "application/pdf" : "text/html";
      return Response.ok().entity(baos.toByteArray()).type(mimeType).build();

    } catch (Exception e) {
      log.error("Error generating BIRT report: " + reportName, e);
      throw new PlatformDataIntegrityException(
          "error.msg.reporting.error", "BIRT Generation Failed: " + e.getMessage());
    }
  }

  @Override
  public Map<String, String> getReportParams(MultivaluedMap<String, String> queryParams) {
    Map<String, String> params = new HashMap<>();
    for (String key : queryParams.keySet()) {
      if (key.startsWith("R_")) {
        params.put(key.substring(2), queryParams.getFirst(key));
      }
    }
    return params;
  }

  private Map<String, Object> getReportParamsObject(MultivaluedMap<String, String> queryParams) {
    Map<String, Object> params = new HashMap<>();
    for (String key : queryParams.keySet()) {
      if (key.startsWith("R_")) {
        params.put(key.substring(2), queryParams.getFirst(key));
      }
    }
    return params;
  }

  private String getReportPath() {
    if (StringUtils.isNotBlank(birtReportsPath)) {
      return birtReportsPath.endsWith(File.separator)
          ? birtReportsPath
          : birtReportsPath + File.separator;
    }
    return System.getProperty("user.home")
        + File.separator
        + ".mifosx"
        + File.separator
        + "birtReports"
        + File.separator;
  }

  @Override
  public List<ReportExportType> getAvailableExportTargets() {
    return List.of(
        new ReportExportType("PDF", "application/pdf"), new ReportExportType("HTML", "text/html"));
  }
}
