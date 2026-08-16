/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.api.ApiParameterHelper;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabasePasswordEncryptor;
import org.apache.fineract.infrastructure.dataqueries.data.ReportExportType;
import org.apache.fineract.infrastructure.report.annotation.ReportService;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.apache.fineract.infrastructure.report.renderer.BirtRenderer;
import org.eclipse.birt.report.engine.api.IEngineTask;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.engine.api.IRunTask;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service("birtReportingProcessService") // Explicitly named for Fineract's ServiceProvider lookup
@Primary
@ReportService(type = "BIRT")
@RequiredArgsConstructor
public class BirtReportingProcessServiceImpl implements ReportingProcessService {

    private final IReportEngine reportEngine;
    private final BirtReportExecutionFactory reportExecutionFactory;
    private final BirtParameterMapper parameterMapper;
    private final BirtContextInjector contextInjector;
    private final Map<String, BirtRenderer> birtRenderers;
    private final BirtPluginProperties birtProperties;
    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final BirtSqlDialectInterpolator sqlDialectInterpolator;
    private final DatabasePasswordEncryptor databasePasswordEncryptor;

    @Override
    public Response processRequest(String reportName, MultivaluedMap<String, String> queryParams) {
        String outputType = resolveOutputType(queryParams);
        Locale locale = ApiParameterHelper.extractLocale(queryParams);
        Map<String, String> reportParams = getReportParams(reportName, queryParams);

        log.info("Generating BIRT report: {} | format: {} | locale: {}", reportName, outputType, locale);

        Path tempDocPath;
        try {
            tempDocPath = Files.createTempFile("birt_export_", ".rptdocument");
        } catch (Exception e) {
            throw new PlatformDataIntegrityException("error.msg.reporting.error", "Failed to create temp file", e);
        }
        String documentPath = tempDocPath.toAbsolutePath().toString();

        try {
            executeReportToDocument(reportName, locale, reportParams, tempDocPath, documentPath);
            return renderReport(reportName, outputType, tempDocPath, documentPath);
        } catch (Exception e) {
            deleteTempQuietly(tempDocPath, documentPath);
            throw e;
        }
    }

    private void executeReportToDocument(
            String reportName, Locale locale, Map<String, String> reportParams, Path tempDocPath, String documentPath) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setReadOnly(true);

        transactionTemplate.execute(status -> {
            IRunTask runTask = null;
            Connection springConnection = null;
            try {
                IReportRunnable design = reportExecutionFactory.createExecutionRunnable(reportName, locale);
                ReportDesignHandle designHandle = (ReportDesignHandle) design.getDesignHandle();

                sqlDialectInterpolator.interpolate(designHandle);

                runTask = reportEngine.createRunTask(design);
                runTask.setErrorHandlingOption(IEngineTask.CANCEL_ON_ERROR);

                springConnection = DataSourceUtils.getConnection(dataSource);
                setConnectionDetail(runTask, springConnection);

                configureLocale(runTask, locale);
                parameterMapper.applyParameters(runTask, reportParams);
                contextInjector.injectContextParameters(runTask);
                runTask.run(documentPath);
                return null;
            } catch (PlatformDataIntegrityException e) {
                // Preserve specific validation messages (missing parameters, invalid values, …)
                throw e;
            } catch (Exception e) {
                log.error("Failed to execute BIRT queries for report: {}", reportName, e);
                throw new PlatformDataIntegrityException(
                        "error.msg.reporting.error", "Report execution failed: " + e.getMessage(), e);
            } finally {
                releaseConnectionQuietly(springConnection);
                if (runTask != null) {
                    closeQuietly(runTask::close);
                }
            }
        });
    }

    private Response renderReport(String reportName, String outputType, Path tempDocPath, String documentPath) {
        try {
            BirtRenderer renderer = getRenderer(outputType);
            return renderer.render(reportEngine, documentPath, reportName);
        } catch (Exception e) {
            throw new PlatformDataIntegrityException(
                    "error.msg.reporting.error", "Failed to initialize report stream", e);
        }
    }

    @Override
    public Map<String, String> getReportParams(String reportName, MultivaluedMap<String, String> queryParams) {
        Map<String, String> params = new HashMap<>();
        queryParams.keySet().stream().filter(k -> k.startsWith("R_")).forEach(k -> {
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
                new ReportExportType("HTML", "html"),
                new ReportExportType("XML", "xml"));
    }

    private void setConnectionDetail(IRunTask runTask, Connection springConnection) throws Exception {
        final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
        final FineractPlatformTenantConnection tenantConnection = tenant.getConnection();

        String jdbcUrl = springConnection.getMetaData().getURL();
        String driverClassName =
                org.apache.fineract.infrastructure.report.util.DataSourceUtils.getDriverClassName(dataSource);

        runTask.getAppContext().put("OdaJDBCDriverClass", driverClassName);
        runTask.getAppContext().put("OdaJDBCDriverUrl", jdbcUrl);
        runTask.getAppContext().put("OdaJDBCDriverUser", tenantConnection.getSchemaUsername());
        runTask.getAppContext()
                .put(
                        "OdaJDBCDriverPassword",
                        databasePasswordEncryptor.decrypt(
                                tenantConnection.getSchemaPassword().trim()));

        runTask.getAppContext().put("OdaJDBCDriverPassInConnection", springConnection);
        runTask.getAppContext().put("OdaJDBCDriverPassInConnectionCloseAfterUse", false);
    }

    private String resolveOutputType(MultivaluedMap<String, String> queryParams) {
        String type = queryParams.getFirst("output-type");
        String upper = StringUtils.defaultIfBlank(type, "HTML").toUpperCase();

        if (!Set.of("HTML", "PDF", "XLS", "XLSX", "CSV", "XML").contains(upper)) {
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

    private void configureLocale(IEngineTask task, Locale locale) {
        if (StringUtils.isNotBlank(birtProperties.getDefaultLocale())) {
            task.setLocale(Locale.forLanguageTag(birtProperties.getDefaultLocale()));
        } else if (locale != null) {
            task.setLocale(locale);
        } else {
            task.setLocale(Locale.ENGLISH);
        }
    }

    private void deleteTempQuietly(Path tempDocPath, String documentPath) {
        try {
            if (tempDocPath != null) {
                Files.deleteIfExists(tempDocPath);
            }
        } catch (Exception e) {
            log.warn("Telemetry - Failed to delete temporary BIRT document: {}", documentPath);
        }
    }

    private void releaseConnectionQuietly(Connection springConnection) {
        if (springConnection != null) {
            try {
                DataSourceUtils.releaseConnection(springConnection, dataSource);
            } catch (Exception e) {
                log.warn("Telemetry - Failed to release BIRT JDBC connection");
            }
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
            log.warn("Telemetry - Failed to close BIRT execution resource");
        }
    }
}
