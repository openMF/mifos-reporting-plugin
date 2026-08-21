/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
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
    private final DatabasePasswordEncryptor databasePasswordEncryptor;
    private final ReportSecurityService reportSecurityService;

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
        final TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setReadOnly(true);
        final AtomicReference<String> capturedSqlError = new AtomicReference<>();
        try {
            transactionTemplate.execute(status -> {
                IRunTask runTask = null;
                Connection birtConnection = null;
                try {
                    final IReportRunnable design = reportExecutionFactory.createExecutionRunnable(reportName, locale);
                    final ReportDesignHandle designHandle = (ReportDesignHandle) design.getDesignHandle();
                    sqlDialectInterpolator.interpolate(designHandle);
                    runTask = reportEngine.createRunTask(design);
                    runTask.setErrorHandlingOption(IEngineTask.CANCEL_ON_ERROR);
                    birtConnection = openReadOnlyConnection();
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
                        markRollbackOnly(status);
                        throw createSqlException(birtSqlError);
                    }
                    return null;
                } catch (PlatformDataIntegrityException e) {
                    markRollbackOnly(status);
                    throw e;
                } catch (Exception e) {
                    final String sqlErrorMessage = extractSqlErrorMessage(e);
                    capturedSqlError.set(sqlErrorMessage);
                    log.error("Failed to execute BIRT report [{}]: {}", reportName, sqlErrorMessage, e);
                    markRollbackOnly(status);
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
                    releaseReadOnlyConnection(birtConnection);
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

    /**
     * Opens the JDBC connection that BIRT executes the report's embedded SQL on, and puts it in a
     * read-only transaction before BIRT can use it.
     *
     * <p>The read-only flag of the surrounding {@link TransactionTemplate} does not reach this
     * connection: Fineract's transaction manager is a {@code JpaTransactionManager}, which applies
     * read-only to the persistence context only and never calls {@link Connection#setReadOnly}. The
     * guard therefore has to be applied here, on the connection BIRT actually receives, so that the
     * database refuses the write rather than the plugin trying to recognise one — SQL built at run
     * time by a report script is invisible to any inspection of the design.
     *
     * <p>What the transaction does and does not cover. {@code INSERT}, {@code UPDATE} and
     * {@code DELETE} are refused on both PostgreSQL and MySQL/MariaDB; DDL is refused on PostgreSQL
     * only, because MySQL and MariaDB commit implicitly before it. It is not a containment boundary
     * against a deliberately hostile {@code .rptdesign} either: a design that issues its own
     * transaction control ({@code COMMIT} followed by {@code START TRANSACTION READ WRITE}) leaves
     * the read-only transaction behind and can write.
     *
     * <p>The boundary that does hold is the database principal, configured per tenant — see
     * {@link #openTenantReportConnection()}. The transaction stays regardless, because a tenant that
     * has not configured one still gets the protection the transaction can give.
     */
    private Connection openReadOnlyConnection() throws SQLException {
        final Connection connection = openTenantReportConnection();
        try {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            beginReadOnlyTransaction(connection);
            /*
             * Opening the transaction here, with a statement of our own,
             * closes the cheapest way out of it: a database stops accepting
             * "SET TRANSACTION READ WRITE" once the transaction has taken
             * its snapshot. Without this, the first statement of a report
             * could be that, and a later one a write.
             */
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }
            return connection;
        } catch (SQLException e) {
            closeQuietly(connection::close);
            throw e;
        }
    }

    /**
     * Opens the connection reports run on, as the tenant's read-only database principal when one is
     * configured.
     *
     * <p>A principal granted only {@code SELECT} is the one boundary a report cannot argue with: it
     * refuses writes, DDL and {@code SET ROLE} alike, and unlike the read-only transaction it cannot
     * be left behind by a report that opens a transaction of its own.
     *
     * <p>The credentials come from the tenant's existing {@code readonly_schema_*} columns, so this
     * needs no new configuration surface — but those columns are empty in a stock deployment, and
     * the principal still has to exist in the database. Until an operator sets both up, reports keep
     * running on the ordinary tenant pool with only the read-only transaction protecting them.
     * Fields left blank fall back to the read-write ones, so the common case of one database and a
     * restricted role needs only a username and password.
     */
    private Connection openTenantReportConnection() throws SQLException {
        final FineractPlatformTenantConnection tenantConnection = requireTenantConnection();
        final String readOnlyUsername = tenantConnection.getReadOnlySchemaUsername();

        if (StringUtils.isBlank(readOnlyUsername)) {
            return dataSource.getConnection();
        }

        final String driverClassName =
                org.apache.fineract.infrastructure.report.util.DataSourceUtils.getDriverClassName(dataSource);
        final String jdbcUrl = FineractPlatformTenantConnection.toJdbcUrl(
                FineractPlatformTenantConnection.resolveProtocol(driverClassName),
                StringUtils.defaultIfBlank(
                        tenantConnection.getReadOnlySchemaServer(), tenantConnection.getSchemaServer()),
                StringUtils.defaultIfBlank(
                        tenantConnection.getReadOnlySchemaServerPort(), tenantConnection.getSchemaServerPort()),
                StringUtils.defaultIfBlank(tenantConnection.getReadOnlySchemaName(), tenantConnection.getSchemaName()),
                StringUtils.defaultIfBlank(
                        tenantConnection.getReadOnlySchemaConnectionParameters(),
                        tenantConnection.getSchemaConnectionParameters()));

        log.debug("Running BIRT report as the tenant's read-only database principal {}", readOnlyUsername);
        return DriverManager.getConnection(
                jdbcUrl,
                readOnlyUsername,
                databasePasswordEncryptor.decrypt(
                        StringUtils.trimToEmpty(tenantConnection.getReadOnlySchemaPassword())));
    }

    /**
     * Starts the read-only transaction on databases whose driver will not start one.
     *
     * <p>{@link Connection#setReadOnly} is enough on PostgreSQL, whose driver opens every transaction
     * with {@code BEGIN READ ONLY}. The MySQL and MariaDB drivers treat it as a replica-routing hint
     * and let every write through untouched, so there the transaction has to be opened explicitly.
     *
     * <p>Deliberately a transaction-scoped statement rather than a session-scoped one: the connection
     * goes back to a pool that Fineract writes through, and {@code SET SESSION} would still be in
     * effect when the next borrower picks it up.
     */
    private void beginReadOnlyTransaction(Connection connection) throws SQLException {
        final String jdbcUrl = connection.getMetaData().getURL();
        if (StringUtils.startsWith(jdbcUrl, "jdbc:postgresql")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("START TRANSACTION READ ONLY");
        }
    }

    private void releaseReadOnlyConnection(Connection connection) {
        if (connection == null) {
            return;
        }
        closeQuietly(connection::rollback);
        closeQuietly(connection::close);
    }

    private FineractPlatformTenantConnection requireTenantConnection() {
        final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
        if (tenant == null) {
            throw new PlatformDataIntegrityException(
                    SQL_ERROR_CODE, "Unable to execute BIRT report because no tenant context is available.");
        }
        final FineractPlatformTenantConnection tenantConnection = tenant.getConnection();
        if (tenantConnection == null) {
            throw new PlatformDataIntegrityException(
                    SQL_ERROR_CODE,
                    "Unable to execute BIRT report because the tenant database connection is unavailable.");
        }
        return tenantConnection;
    }

    private void setConnectionDetail(IRunTask runTask, Connection birtConnection) throws Exception {
        requireTenantConnection();
        final String jdbcUrl = birtConnection.getMetaData().getURL();
        final String driverClassName =
                org.apache.fineract.infrastructure.report.util.DataSourceUtils.getDriverClassName(dataSource);
        runTask.getAppContext().put("OdaJDBCDriverClass", driverClassName);
        runTask.getAppContext().put("OdaJDBCDriverUrl", jdbcUrl);
        /*
         * No credentials are published to the report's app context. BIRT's
         * JDBC ODA driver takes the pass-in connection and returns before it
         * ever looks at a user or password, so entries for them would only
         * put the tenant's decrypted database password in a map that report
         * scripts can reach — and would now name the wrong principal.
         */
        /*
         * BIRT must not close the connection, nor take it out of the
         * read-only transaction it was opened in.
         */
        final Connection safeConnection = wrapConnectionToPreventClose(birtConnection);
        runTask.getAppContext().put("OdaJDBCDriverPassInConnection", safeConnection);
        runTask.getAppContext().put("OdaJDBCDriverPassInConnectionCloseAfterUse", false);
    }

    private Connection wrapConnectionToPreventClose(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    final String methodName = method.getName();
                    if ("close".equals(methodName)) {
                        log.debug("Prevented BIRT from closing the Spring-managed tenant connection.");
                        return null;
                    }
                    if ("isClosed".equals(methodName)) {
                        return false;
                    }
                    if ("setReadOnly".equals(methodName) || "setAutoCommit".equals(methodName)) {
                        log.debug("Ignored BIRT attempt to call {} on the read-only report connection.", methodName);
                        return null;
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
    }

    private void markRollbackOnly(TransactionStatus status) {
        if (status != null && !status.isCompleted()) {
            status.setRollbackOnly();
        }
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
            rejectServerManagedParameter(parameterName);
            final String value = queryParams.getFirst(key);
            if (StringUtils.isNotBlank(value)) {
                params.put(parameterName, value);
            }
        });
        return params;
    }

    /**
     * Rejects a request that tries to supply a parameter the server derives from the authenticated
     * user. Dropping such a value silently would leave the caller believing the scope they asked for
     * was applied, so the request fails instead.
     */
    private void rejectServerManagedParameter(String parameterName) {
        if (BirtParameterMapper.SERVER_MANAGED_PARAMETERS.contains(parameterName.toLowerCase(Locale.ROOT))) {
            throw new PlatformDataIntegrityException(
                    "error.msg.reporting.parameter.not.allowed",
                    "Parameter '" + parameterName
                            + "' is derived from the authenticated user and cannot be supplied by the client.");
        }
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
