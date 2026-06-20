/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.api.ApiParameterHelper;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.dataqueries.data.ReportExportType;
import org.apache.fineract.infrastructure.report.annotation.ReportService;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.apache.fineract.infrastructure.report.renderer.BirtRenderer;
import org.eclipse.birt.report.engine.api.IEngineTask;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Primary
@ReportService(type = "BIRT")
@RequiredArgsConstructor
public class BirtReportingProcessServiceImpl implements ReportingProcessService {

  private final IReportEngine reportEngine;
  private final BirtReportExecutionFactory reportExecutionFactory;
  private final BirtDataSourceConfigurer dataSourceConfigurer;
  private final BirtParameterMapper parameterMapper;
  private final Map<String, BirtRenderer> birtRenderers;
  private final BirtPluginProperties birtProperties; // Injected

  @Override
  public Response processRequest(String reportName, MultivaluedMap<String, String> queryParams) {
    String outputType = resolveOutputType(queryParams);
    Locale locale = ApiParameterHelper.extractLocale(queryParams);
    Map<String, String> reportParams = getReportParams(reportName, queryParams);

    log.info(
        "Generating BIRT report: {} | format: {} | locale: {}", reportName, outputType, locale);

    IRunAndRenderTask task = null;
    try {
      IReportRunnable design = reportExecutionFactory.createExecutionRunnable(reportName, locale);
      ReportDesignHandle designHandle = (ReportDesignHandle) design.getDesignHandle();

      dataSourceConfigurer.configureAll(designHandle);

      task = reportEngine.createRunAndRenderTask(design);
      task.setErrorHandlingOption(IEngineTask.CANCEL_ON_ERROR);

      configureLocale(task, locale);
      parameterMapper.applyParameters(task, reportParams);

      BirtRenderer renderer = getRenderer(outputType);
      return renderer.render(task, reportName);

    } catch (Exception e) {
      log.error("Failed to generate BIRT report: {}", reportName, e);
      throw new PlatformDataIntegrityException(
          "error.msg.reporting.error", "Report generation failed: " + e.getMessage(), e);
    } finally {
      if (task != null) {
        try {
          task.close();
        } catch (Exception e) {
          log.warn("Failed to close BIRT report task for report: {}", reportName, e);
        }
      }
    }
  }

  private String resolveOutputType(MultivaluedMap<String, String> queryParams) {
    String type = queryParams.getFirst("output-type");
    String upper = StringUtils.defaultIfBlank(type, "HTML").toUpperCase();

    if (!Set.of("HTML", "PDF", "XLS", "XLSX", "CSV").contains(upper)) {
      throw new PlatformDataIntegrityException(
          "error.msg.invalid.outputType", "Unsupported output type: " + type);
    }
    return upper;
  }

  private BirtRenderer getRenderer(String outputType) {
    BirtRenderer renderer = birtRenderers.get(outputType);
    if (renderer == null) {
      throw new PlatformDataIntegrityException(
          "error.msg.invalid.outputType", "No renderer registered for output type: " + outputType);
    }
    return renderer;
  }

  /** Configures report locale using BirtProperties */
  private void configureLocale(IRunAndRenderTask task, Locale locale) {
    // Priority 1: Configured default locale in application properties
    if (StringUtils.isNotBlank(birtProperties.getDefaultLocale())) {
      task.setLocale(Locale.forLanguageTag(birtProperties.getDefaultLocale()));
      log.debug("Using configured default locale: {}", birtProperties.getDefaultLocale());
    }
    // Priority 2: Locale from request parameter
    else if (locale != null) {
      task.setLocale(locale);
      log.debug("Using locale from request: {}", locale);
    }
    // Priority 3: System default (fallback)
    else {
      task.setLocale(Locale.ENGLISH);
      log.debug("Using fallback locale: English");
    }
  }

  @Override
  public Map<String, String> getReportParams(
      String reportName, MultivaluedMap<String, String> queryParams) {
    Map<String, String> params = new HashMap<>();
    queryParams.keySet().stream()
        .filter(k -> k.startsWith("R_"))
        .forEach(
            k -> {
              String key = k.substring(2);
              String value = queryParams.getFirst(k);
              if (StringUtils.isNotBlank(value)) {
                params.put(key, value);
              }
            });
    return params;
  }

  @Override
  public List<ReportExportType> getAvailableExportTargets() {
    return List.of(
        new ReportExportType("PDF", "pdf"),
        new ReportExportType("XLS", "xls"),
        new ReportExportType("XLSX", "xlsx"),
        new ReportExportType("CSV", "csv"),
        new ReportExportType("HTML", "html"));
  }
}
