/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import java.util.Locale;

import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.eclipse.birt.report.model.api.core.IDesignElement;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates execution-local BIRT report runnables from cached report templates.
 *
 * <p>The cached runnable returned by {@link BirtReportLoader} must be treated as immutable template
 * state. Tenant-specific datasource values are written later in the execution flow, so each
 * execution needs an isolated report design handle before datasource configuration occurs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BirtReportExecutionFactory {

  private final IReportEngine reportEngine;
  private final BirtReportLoader reportLoader;
  private final ReportErrorHandler reportErrorHandler;

  /**
   * Creates a per-execution runnable by copying the cached template design handle and opening a new
   * runnable from that copied handle.
   *
   * @param reportName the base report name
   * @param locale the requested locale, or {@code null}
   * @return an execution-local runnable safe for tenant-specific datasource mutation
   */
  public IReportRunnable createExecutionRunnable(String reportName, Locale locale) {
    try {
      IReportRunnable template = reportLoader.loadReport(reportName, locale);
      ReportDesignHandle templateHandle = (ReportDesignHandle) template.getDesignHandle();

      IDesignElement copiedElement = templateHandle.copy();
      ReportDesignHandle executionHandle = (ReportDesignHandle) copiedElement.getHandle(null);
      
      executionHandle.setFileName(template.getReportName());

      IReportRunnable executionRunnable = reportEngine.openReportDesign(executionHandle);
      log.debug("Created execution-local BIRT runnable for report: {}", reportName);
      return executionRunnable;
    } catch (PlatformDataIntegrityException e) {
      throw e;
    } catch (Exception e) {
      log.error("Failed to create execution-local BIRT runnable for report: {}", reportName, e);
      throw reportErrorHandler.reportError(
          "error.msg.reporting.report.execution.copy.failed",
          "Failed to create execution-local report design: " + reportName,
          e);
    }
  }
}
