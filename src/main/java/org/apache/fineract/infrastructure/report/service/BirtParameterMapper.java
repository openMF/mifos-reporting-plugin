/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.eclipse.birt.report.engine.api.IGetParameterDefinitionTask;
import org.eclipse.birt.report.engine.api.IParameterDefn;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.springframework.stereotype.Component;

/** Handles parameter mapping and authorization context injection for BIRT reports. */
@Slf4j
@Component
@RequiredArgsConstructor
public class BirtParameterMapper {

  private final PlatformSecurityContext securityContext;
  private final ReportErrorHandler reportErrorHandler;
  private final IReportEngine reportEngine;

  // Reduced down strictly to row-level and contextual security scoping params
  private static final Set<String> SERVER_MANAGED_PARAMETERS = Set.of("userhierarchy", "userid");

  public void applyParameters(IRunAndRenderTask task, Map<String, String> reportParams) {
    if (task == null) {
      throw reportErrorHandler.reportError("error.msg.reporting.error", "Task cannot be null");
    }

    log.debug("Applying parameters to BIRT report task");

    IGetParameterDefinitionTask paramTask = null;
    try {
      paramTask = reportEngine.createGetParameterDefinitionTask(task.getReportRunnable());

      for (Object paramObj : paramTask.getParameterDefns(false)) {
        IParameterDefn paramDef = (IParameterDefn) paramObj;
        String paramName = paramDef.getName();

        if (SERVER_MANAGED_PARAMETERS.contains(paramName)) {
          continue;
        }

        String paramValue = reportParams.get(paramName);

        if (StringUtils.isBlank(paramValue)) {
          throw reportErrorHandler.reportError(
              "error.msg.reporting.missing.parameter",
              "Required parameter not provided: " + paramName);
        }

        setTypedParameter(task, paramDef, paramName, paramValue);
      }

    } catch (Exception e) {
      log.error("Error while processing BIRT parameter definitions", e);
      throw reportErrorHandler.reportError(
          "error.msg.reporting.error", "Failed to process report parameters", e);
    } finally {
      if (paramTask != null) {
        try {
          paramTask.close();
        } catch (Exception e) {
          log.warn("Error closing IGetParameterDefinitionTask", e);
        }
      }
    }

    injectContextParameters(task);
  }

  private void setTypedParameter(
      IRunAndRenderTask task, IParameterDefn paramDef, String name, String value) {
    try {
      switch (paramDef.getDataType()) {
        case IParameterDefn.TYPE_INTEGER:
          task.setParameterValue(name, Integer.parseInt(value));
          break;
        case IParameterDefn.TYPE_FLOAT:
        case IParameterDefn.TYPE_DECIMAL:
          task.setParameterValue(name, Double.parseDouble(value));
          break;
        case IParameterDefn.TYPE_DATE:
        case IParameterDefn.TYPE_DATE_TIME:
          Date date = parseDate(value);
          task.setParameterValue(name, new java.sql.Date(date.getTime()));
          break;
        case IParameterDefn.TYPE_BOOLEAN:
          task.setParameterValue(name, Boolean.parseBoolean(value));
          break;
        default:
          task.setParameterValue(name, value);
          break;
      }
      log.trace("Set parameter {} = {}", name, value);
    } catch (Exception e) {
      log.error("Failed to set parameter: {} with value: {}", name, value, e);
      throw reportErrorHandler.reportError(
          "error.msg.reporting.invalid.parameter",
          "Invalid value for parameter '" + name + "': " + value,
          e);
    }
  }

  private Date parseDate(String value) {
    try {
      SimpleDateFormat sdf = new SimpleDateFormat("dd MMMM yyyy", Locale.ENGLISH);
      return sdf.parse(value);
    } catch (Exception e) {
      throw new PlatformDataIntegrityException(
          "error.msg.reporting.invalid.date", "Invalid date format. Expected: 'dd MMMM yyyy'", e);
    }
  }

  private void injectContextParameters(IRunAndRenderTask task) {
    try {
      var currentUser = securityContext.authenticatedUser();
      var tenant = ThreadLocalContextUtil.getTenant();

      // Maintain only critical contextual row-level row scoping markers
      task.setParameterValue("userhierarchy", currentUser.getOffice().getHierarchy());
      task.setParameterValue("userid", currentUser.getId());

      log.debug(
          "Security scope context parameters injected successfully for tenant: {}",
          tenant.getName());
    } catch (Exception e) {
      log.error("Failed to inject context parameters", e);
      throw reportErrorHandler.reportError(
          "error.msg.reporting.error", "Failed to inject user security context parameters", e);
    }
  }
}
