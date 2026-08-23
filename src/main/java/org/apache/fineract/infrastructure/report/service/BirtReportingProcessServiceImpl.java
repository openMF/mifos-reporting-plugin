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
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
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
import org.eclipse.birt.report.engine.api.IRunTask;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service("birtReportingProcessService")
@Primary
@ReportService(type = "BIRT")
@RequiredArgsConstructor
public class BirtReportingProcessServiceImpl implements ReportingProcessService {

    private static final String SQL_ERROR_CODE = "error.msg.reporting.sql.error";
    private static final String UNKNOWN_SQL_ERROR = "Unknown SQL error";
    private static final Set<String> SUPPORTED_OUTPUT_TYPES = Set.of("HTML", "PDF", "XLS", "XLSX", "CSV", "XML");
    private static final String CONNECTION_CLOSED = "connection is closed";
    private final IReportEngine reportEngine;
    private final BirtReportExecutionFactory reportExecutionFactory;
    private final BirtParameterMapper parameterMapper;
    private final BirtContextInjector contextInjector;
    private final Map<String, BirtRenderer> birtRenderers;
    private final BirtPluginProperties birtProperties;
    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final BirtSqlDialectInterpolator sqlDialectInterpolator;
    private final ReportSecurityService reportSecurityService;
    private final BirtReadOnlyConnectionFactory connectionFactory;

    @Override
    public Response processRequest(String reportName, MultivaluedMap<String, String> queryParams) {
        reportSecurityService.checkReportExecutionPermission(reportName);
        final String outputType = resolveOutputType(queryParams);
        final Locale locale = ApiParameterHelper.extractLocale(queryParams);
        final Map<String, String> reportParams = getReportParams(reportName, queryParams);
        log.info("Generating BIRT report: {} | format: {} | locale: {}", reportName, outputType, locale);
        final Path tempDocPath = createTemporaryDocument();
        final String documentPath = tempDocPath.toAbsolutePath().toString();
        try {
            executeReportToDocument(reportName, locale, reportParams, documentPath);
            return renderReport(reportName, outputType, documentPath);
        } catch (PlatformDataIntegrityException e) {
            deleteTempQuietly(tempDocPath, documentPath);
            throw e;
        } catch (Exception e) {
            deleteTempQuietly(tempDocPath, documentPath);
            throw new PlatformDataIntegrityException(
                    SQL_ERROR_CODE, "Report execution failed due to a SQL error: " + extractSqlErrorMessage(e), e);
        }
    }

    private Path createTemporaryDocument() {
        try {
            return Files.createTempFile("birt_export_", ".rptdocument");
        } catch (Exception e) {
            throw new PlatformDataIntegrityException(
                    "error.msg.reporting.error", "Failed to create temporary BIRT report document.", e);
        }
    }

    private void executeReportToDocument(
            String reportName, Locale locale, Map<String, String> reportParams, String documentPath) {
        /*
         * This transaction no longer scopes the report's own SQL: that runs
         * on a separate connection this method opens and closes itself, and
         * nothing set here reaches it. What is left inside it is Fineract's
         * work around the report — loading the design, which reads the
         * reports directory from c_external_service_properties, and reading
         * the authenticated user for the context parameters — so it stays,
         * read-only, for that.
         *
         * Nothing here marks it rollback-only. Every failure below leaves by
         * throwing a PlatformDataIntegrityException, which is a
         * RuntimeException, and TransactionTemplate.execute already rolls the
         * transaction back for one of those. Marking it as well protected
         * nothing and read as though it protected the report's SQL, which
         * runs on a connection this transaction never sees.
         */
        final TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setReadOnly(true);
        final AtomicReference<String> capturedSqlError = new AtomicReference<>();
        try {
            transactionTemplate.execute(transactionStatus -> {
                IRunTask runTask = null;
                Connection birtConnection = null;
                try {
                    final IReportRunnable design = reportExecutionFactory.createExecutionRunnable(reportName, locale);
                    final ReportDesignHandle designHandle = (ReportDesignHandle) design.getDesignHandle();
                    sqlDialectInterpolator.interpolate(designHandle);
                    runTask = reportEngine.createRunTask(design);
                    runTask.setErrorHandlingOption(IEngineTask.CANCEL_ON_ERROR);
                    birtConnection = connectionFactory.open();
                    setConnectionDetail(runTask, birtConnection);
                    configureLocale(runTask, locale);
                    parameterMapper.applyParameters(runTask, reportParams);
                    contextInjector.injectContextParameters(runTask);
                    runTask.run(documentPath);
                    /*
                     * BIRT does not always propagate JDBC failures as the
                     * exception thrown by run(). Some JDBC/ODA failures are
                     * stored in the task error collection instead.
                     */
                    final String birtSqlError = extractBirtTaskError(runTask);
                    if (StringUtils.isNotBlank(birtSqlError)) {
                        capturedSqlError.set(birtSqlError);
                        throw createSqlException(birtSqlError);
                    }
                    return null;
                } catch (PlatformDataIntegrityException e) {
                    throw e;
                } catch (Exception e) {
                    final String sqlErrorMessage = extractSqlErrorMessage(e);
                    capturedSqlError.set(sqlErrorMessage);
                    log.error("Failed to execute BIRT report [{}]: {}", reportName, sqlErrorMessage, e);
                    throw createSqlException(sqlErrorMessage);
                } finally {
                    if (runTask != null) {
                        closeQuietly(runTask::close);
                    }
                    /*
                     * The BIRT connection is not the Spring transaction's
                     * connection, so this method owns its lifecycle. BIRT
                     * only ever sees the close-proof proxy.
                     */
                    connectionFactory.release(birtConnection);
                }
            });
        } catch (PlatformDataIntegrityException e) {
            /*
             * Preserve the original Fineract exception.
             *
             * Re-wrapping it would unnecessarily change its message/cause
             * chain and could hide the SQL exception.
             */
            throw e;
        } catch (Exception e) {
            String sqlErrorMessage = capturedSqlError.get();
            if (StringUtils.isBlank(sqlErrorMessage)) {
                sqlErrorMessage = extractSqlErrorMessage(e);
            }
            throw createSqlException(sqlErrorMessage);
        }
    }

    private PlatformDataIntegrityException createSqlException(String sqlErrorMessage) {
        final String normalizedMessage = StringUtils.defaultIfBlank(sqlErrorMessage, UNKNOWN_SQL_ERROR);
        return new PlatformDataIntegrityException(
                SQL_ERROR_CODE, "Report execution failed due to a SQL error: " + normalizedMessage);
    }

    private String extractBirtTaskError(IRunTask runTask) {
        final List<?> errors = runTask.getErrors();
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        String bestError = null;
        for (Object error : errors) {
            if (error == null) {
                continue;
            }
            final String message;
            if (error instanceof Throwable throwable) {
                message = extractSqlErrorMessage(throwable);
            } else {
                message = error.toString();
            }
            if (StringUtils.isBlank(message) || UNKNOWN_SQL_ERROR.equalsIgnoreCase(message)) {
                continue;
            }
            if (isConnectionClosedMessage(message)) {
                continue;
            }
            if (isSqlErrorMessage(message)) {
                return cleanSqlMessage(message);
            }
            if (bestError == null) {
                bestError = cleanSqlMessage(message);
            }
        }
        return bestError;
    }

    private String extractSqlErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return UNKNOWN_SQL_ERROR;
        }
        Throwable current = throwable;
        String fallbackMessage = null;
        while (current != null) {
            final String message = current.getMessage();
            if (StringUtils.isNotBlank(message) && !isConnectionClosedMessage(message)) {
                final String cleaned = cleanSqlMessage(message);
                /*
                 * Prefer JDBC/database errors over generic BIRT
                 * wrapper exceptions.
                 */
                if (current instanceof SQLException || isJdbcException(current) || isSqlErrorMessage(cleaned)) {
                    return cleaned;
                }
                if (fallbackMessage == null) {
                    fallbackMessage = cleaned;
                }
            }
            current = current.getCause();
        }
        return StringUtils.defaultIfBlank(fallbackMessage, UNKNOWN_SQL_ERROR);
    }

    private boolean isJdbcException(Throwable throwable) {
        final String className = throwable.getClass().getName();
        return className.contains("PSQLException")
                || className.contains("JDBCException")
                || className.contains("OdaException")
                || className.contains("SQLException");
    }

    private boolean isSqlErrorMessage(String message) {
        if (StringUtils.isBlank(message)) {
            return false;
        }
        final String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("sql error")
                || lower.contains("syntax error")
                || lower.contains("position:")
                || lower.contains("constraint")
                || lower.contains("relation")
                || lower.contains("column")
                || lower.contains("duplicate key")
                || lower.contains("violates")
                || lower.contains("permission denied")
                || lower.contains("does not exist");
    }

    private boolean isConnectionClosedMessage(String message) {
        return StringUtils.isNotBlank(message)
                && message.toLowerCase(Locale.ROOT).contains(CONNECTION_CLOSED);
    }

    private String cleanSqlMessage(String message) {
        if (StringUtils.isBlank(message)) {
            return UNKNOWN_SQL_ERROR;
        }
        String cleaned = message.trim();
        final String[] prefixes = {"SQL error #1:", "SQL error:", "ERROR:"};
        for (String prefix : prefixes) {
            final int index = cleaned.indexOf(prefix);
            if (index >= 0) {
                cleaned = cleaned.substring(index + prefix.length()).trim();
                break;
            }
        }
        cleaned = cleaned.replaceAll("(?m)^[ \\t]+", "");
        cleaned = cleaned.replaceAll("[;]+$", "");
        return cleaned.trim();
    }

    private void setConnectionDetail(IRunTask runTask, Connection birtConnection) throws Exception {
        final String jdbcUrl = birtConnection.getMetaData().getURL();
        final String driverClassName =
                org.apache.fineract.infrastructure.report.util.DataSourceUtils.getDriverClassName(dataSource);
        runTask.getAppContext().put("OdaJDBCDriverClass", driverClassName);
        runTask.getAppContext().put("OdaJDBCDriverUrl", jdbcUrl);
        /*
         * No credentials go into the app context: BIRT takes the pass-in
         * connection and returns before it looks at a user or password, and
         * report scripts can read that map. The proxy stops BIRT closing the
         * connection or leaving its read-only transaction.
         */
        final Connection safeConnection = connectionFactory.guard(birtConnection);
        runTask.getAppContext().put("OdaJDBCDriverPassInConnection", safeConnection);
        runTask.getAppContext().put("OdaJDBCDriverPassInConnectionCloseAfterUse", false);
    }

    private Response renderReport(String reportName, String outputType, String documentPath) {
        try {
            final BirtRenderer renderer = getRenderer(outputType);
            return renderer.render(reportEngine, documentPath, reportName);
        } catch (Exception e) {
            throw new PlatformDataIntegrityException(
                    "error.msg.reporting.error", "Failed to initialize report stream.", e);
        }
    }

    @Override
    public Map<String, String> getReportParams(String reportName, MultivaluedMap<String, String> queryParams) {
        final Map<String, String> params = new HashMap<>();
        queryParams.keySet().stream().filter(key -> key.startsWith("R_")).forEach(key -> {
            final String parameterName = key.substring(2);
            if (isServerManagedParameter(parameterName)) {
                return;
            }
            final String value = queryParams.getFirst(key);
            if (StringUtils.isNotBlank(value)) {
                params.put(parameterName, value);
            }
        });
        return params;
    }

    /**
     * Drops a parameter the server derives from the authenticated user, and says so in the log.
     *
     * <p>The value is dropped, and was already unreachable before this: {@link
     * BirtParameterMapper#applyParameters} skips these names, and the context injector overwrites
     * both afterwards. Failing the request instead would turn a no-op into an outage for callers
     * that still send them — stored {@code stretchy_report_param_map} entries carrying the
     * Pentaho-era {@code R_userhierarchy} reach this through report mailing jobs, where the failure
     * would happen in a background run with nobody watching.
     */
    private boolean isServerManagedParameter(String parameterName) {
        if (!BirtParameterMapper.SERVER_MANAGED_PARAMETERS.contains(parameterName.toLowerCase(Locale.ROOT))) {
            return false;
        }
        log.warn("Ignoring client-supplied parameter '{}': it is derived from the authenticated user.", parameterName);
        return true;
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

    private String resolveOutputType(MultivaluedMap<String, String> queryParams) {
        final String type = queryParams.getFirst("output-type");
        final String outputType = StringUtils.defaultIfBlank(type, "HTML").toUpperCase(Locale.ROOT);
        if (!SUPPORTED_OUTPUT_TYPES.contains(outputType)) {
            throw new PlatformDataIntegrityException(
                    "error.msg.invalid.outputType", "Unsupported output type: " + type);
        }
        return outputType;
    }

    private BirtRenderer getRenderer(String outputType) {
        final BirtRenderer renderer = birtRenderers.get(outputType);
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
            log.warn("Failed to delete temporary BIRT document: {}", documentPath, e);
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
            log.warn("Failed to close BIRT execution resource.", e);
        }
    }
}
