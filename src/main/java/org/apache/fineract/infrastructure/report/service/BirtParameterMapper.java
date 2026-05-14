/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabasePasswordEncryptor;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.eclipse.birt.report.engine.api.IGetParameterDefinitionTask;
import org.eclipse.birt.report.engine.api.IParameterDefn;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Handles parameter mapping and injection for BIRT reports.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BirtParameterMapper {

    private final PlatformSecurityContext securityContext;
    private final DatabasePasswordEncryptor databasePasswordEncryptor;
    private final ReportErrorHandler reportErrorHandler;
    private final IReportEngine reportEngine;           // Injected here
    private final DataSource tenantDataSource;

    private static final Set<String> SERVER_MANAGED_PARAMETERS = Set.of(
            "tenantUrl", "userhierarchy", "username", "password", "userid"
    );

    /**
     * Applies all parameters to the BIRT task (user-provided + server context)
     */
    public void applyParameters(IRunAndRenderTask task, Map<String, String> reportParams) {
        if (task == null) {
            throw reportErrorHandler.reportError("error.msg.reporting.error", "Task cannot be null");
        }

        log.debug("Applying parameters to BIRT report task");

        IGetParameterDefinitionTask paramTask = null;
        try {
            // Correct way: Use injected IReportEngine
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
            throw reportErrorHandler.reportError("error.msg.reporting.error",
                    "Failed to process report parameters", e);
        } finally {
            if (paramTask != null) {
                try {
                    paramTask.close();
                } catch (Exception e) {
                    log.warn("Error closing IGetParameterDefinitionTask", e);
                }
            }
        }

        // Inject server-side context parameters
        injectContextParameters(task);
    }

    /**
     * Sets parameter with correct data type
     */
    private void setTypedParameter(IRunAndRenderTask task, IParameterDefn paramDef,
                                   String name, String value) {

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
            throw reportErrorHandler.reportError("error.msg.reporting.invalid.parameter",
                    "Invalid value for parameter '" + name + "': " + value, e);
        }
    }

    private Date parseDate(String value) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("dd MMMM yyyy", Locale.ENGLISH);
            return sdf.parse(value);
        } catch (Exception e) {
            throw new PlatformDataIntegrityException("error.msg.reporting.invalid.date",
                    "Invalid date format. Expected: 'dd MMMM yyyy'", e);
        }
    }

    /**
     * Injects tenant and user context parameters
     */
    private void injectContextParameters(IRunAndRenderTask task) {
        try {
            var currentUser = securityContext.authenticatedUser();
            var tenant = ThreadLocalContextUtil.getTenant();
            var conn = tenant.getConnection();

            String tenantUrl = buildTenantJdbcUrl(conn);
            String username = getDbUsername(conn);
            String password = getDbPassword(conn);

            task.setParameterValue("tenantUrl", tenantUrl);
            task.setParameterValue("userhierarchy", currentUser.getOffice().getHierarchy());
            task.setParameterValue("userid", currentUser.getId());
            task.setParameterValue("username", username);
            task.setParameterValue("password", password);

            log.debug("Context parameters injected successfully for tenant: {}", tenant.getName());

        } catch (Exception e) {
            log.error("Failed to inject context parameters", e);
            throw reportErrorHandler.reportError("error.msg.reporting.error",
                    "Failed to inject tenant/user context parameters", e);
        }
    }

    private String buildTenantJdbcUrl(FineractPlatformTenantConnection conn) {
        String protocol = BirtDataSourceConfigurer.toProtocol(tenantDataSource);

        return org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection
                .toJdbcUrl(protocol,
                        conn.getSchemaServer(),
                        conn.getSchemaServerPort(),
                        conn.getSchemaName(),
                        conn.getSchemaConnectionParameters());
    }

    private String getDbUsername(FineractPlatformTenantConnection conn) {
        if (StringUtils.isBlank(conn.getSchemaUsername())) {
            log.error("No username found in DB for tenant");
            throw reportErrorHandler.reportError("error.msg.reporting.username.notfound", "No username found in DB for tenant");
        }
        return conn.getSchemaUsername().trim();
    }

    private String getDbPassword(FineractPlatformTenantConnection conn) {
        if (StringUtils.isBlank(conn.getSchemaPassword())) {
            log.error("No password found in DB for tenant");
            throw reportErrorHandler.reportError("error.msg.reporting.password.notfound", "No password found in DB for tenant");
        }

        try {
            return databasePasswordEncryptor.decrypt(conn.getSchemaPassword()).trim();
        } catch (Exception e) {
            log.error("Password decryption failed", e);
            throw reportErrorHandler.reportError("error.msg.reporting.password.decryption",
                    "Failed to decrypt database password", e);
        }
    }
}