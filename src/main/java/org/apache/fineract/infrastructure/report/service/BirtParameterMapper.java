/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.eclipse.birt.report.engine.api.IGetParameterDefinitionTask;
import org.eclipse.birt.report.engine.api.IParameterDefn;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IRunTask;
import org.springframework.stereotype.Component;

/**
 * Handles parameter mapping and pre-execution validation for BIRT reports.
 * Collects ALL missing required parameters and throws a single, detailed
 * PlatformDataIntegrityException so the API returns useful information.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BirtParameterMapper {

    private final ReportErrorHandler reportErrorHandler;
    private final IReportEngine reportEngine;

    // Injected separately by BirtContextInjector – never required from the client
    private static final Set<String> SERVER_MANAGED_PARAMETERS = Set.of("userhierarchy", "userid");

    /**
     * Validates and applies report parameters to the BIRT run task.
     *
     * @param task          BIRT run task
     * @param reportParams  map of parameters after the "R_" prefix has been stripped
     *                      (e.g. "startDate", "branch", …)
     * @throws PlatformDataIntegrityException when one or more required parameters are missing
     *                                        or have an invalid type/value
     */
    public void applyParameters(IRunTask task, Map<String, String> reportParams) {
        if (task == null) {
            throw reportErrorHandler.reportError("error.msg.reporting.error", "Task cannot be null");
        }

        log.debug("Validating and applying parameters to BIRT report task");

        IGetParameterDefinitionTask paramTask = null;
        try {
            paramTask = reportEngine.createGetParameterDefinitionTask(task.getReportRunnable());

            List<String> missingRequired = new ArrayList<>();

            for (Object paramObj : paramTask.getParameterDefns(false)) {
                IParameterDefn paramDef = (IParameterDefn) paramObj;
                String paramName = paramDef.getName();

                if (SERVER_MANAGED_PARAMETERS.contains(paramName)) {
                    continue;
                }

                String paramValue = reportParams != null ? reportParams.get(paramName) : null;

                // Only enforce parameters that the report designer marked as required
                if (paramDef.isRequired() && StringUtils.isBlank(paramValue)) {
                    missingRequired.add(paramName);
                    continue; // collect all, do not fail early
                }

                // Optional parameter with no value → leave BIRT default / null
                if (StringUtils.isBlank(paramValue)) {
                    continue;
                }

                setTypedParameter(task, paramDef, paramName, paramValue);
            }

            if (!missingRequired.isEmpty()) {
                String details = String.join(", ", missingRequired);
                // Use a dedicated error code so clients / i18n can react specifically
                throw reportErrorHandler.reportError(
                        "error.msg.reporting.missing.parameter", "Required parameter(s) not provided: " + details);
            }

        } catch (PlatformDataIntegrityException e) {
            // Re-throw our own validation exceptions unchanged so the message stays specific
            throw e;
        } catch (Exception e) {
            log.error("Error while processing BIRT parameter definitions", e);
            throw reportErrorHandler.reportError("error.msg.reporting.error", "Failed to process report parameters", e);
        } finally {
            if (paramTask != null) {
                closeQuietly(paramTask::close);
            }
        }
    }

    private void setTypedParameter(IRunTask task, IParameterDefn paramDef, String name, String value) {
        try {
            switch (paramDef.getDataType()) {
                case IParameterDefn.TYPE_INTEGER -> task.setParameterValue(name, Integer.parseInt(value));
                case IParameterDefn.TYPE_FLOAT, IParameterDefn.TYPE_DECIMAL ->
                    task.setParameterValue(name, Double.parseDouble(value));
                case IParameterDefn.TYPE_DATE, IParameterDefn.TYPE_DATE_TIME ->
                    task.setParameterValue(name, parseDate(value));
                case IParameterDefn.TYPE_BOOLEAN -> task.setParameterValue(name, Boolean.parseBoolean(value));
                default -> task.setParameterValue(name, value);
            }
            log.trace("Set parameter {} = {}", name, value);
        } catch (Exception e) {
            log.error("Failed to set parameter: {} with value: {}", name, value, e);
            throw reportErrorHandler.reportError(
                    "error.msg.reporting.invalid.parameter", "Invalid value for parameter '" + name + "': " + value, e);
        }
    }

    private Date parseDate(String value) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);
            LocalDate localDate = LocalDate.parse(value, formatter);
            return Date.valueOf(localDate);
        } catch (Exception e) {
            throw new PlatformDataIntegrityException(
                    "error.msg.reporting.invalid.date", "Invalid date format. Expected: 'dd MMMM yyyy'", e);
        }
    }

    @FunctionalInterface
    private interface BirtResource {
        void close() throws Exception;
    }

    private void closeQuietly(BirtResource resource) {
        try {
            if (resource != null) {
                resource.close();
            }
        } catch (Exception e) {
            log.warn("Error closing BIRT resource", e);
        }
    }
}
