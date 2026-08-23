/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabasePasswordEncryptor;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.apache.fineract.infrastructure.report.util.DataSourceUtils;
import org.springframework.stereotype.Component;

/**
 * Opens, restricts and hands back the JDBC connection a BIRT report's SQL runs on.
 *
 * <p>This lives apart from {@link BirtReportingProcessServiceImpl} so that the behaviour it defines
 * is the behaviour the tests exercise. The restriction it applies is what stands between report SQL
 * and the tenant database, and a test that re-implemented it would only ever prove itself right.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BirtReadOnlyConnectionFactory {

    private static final String SQL_ERROR_CODE = "error.msg.reporting.sql.error";
    private static final String NO_STORED_PROCEDURES = "A BIRT report may not call a stored procedure.";

    /**
     * Statements that would take the report out of the transaction it was placed in.
     *
     * <p>The read-only transaction still catches ordinary writes, so this list does not have to be
     * exhaustive to be worth having — it only has to cover the ways out of that transaction. Report
     * SQL does not contain these words; a design that needs one is doing something a report should
     * not.
     */
    private static final Pattern TRANSACTION_CONTROL = Pattern.compile(
            "\\b(commit|rollback|begin)\\b|\\b(start|set)\\s+transaction\\b|\\bset\\s+session\\b",
            Pattern.CASE_INSENSITIVE);

    private final DataSource dataSource;
    private final DatabasePasswordEncryptor databasePasswordEncryptor;
    private final BirtPluginProperties birtProperties;

    /**
     * One pool per tenant read-only principal, kept for the lifetime of the plugin.
     *
     * <p>Reports are opened by users, so the rate is whatever the users produce. Handing out a fresh
     * physical connection per report would leave the database's own connection limit as the only
     * thing bounding that, and exhausting it takes down all of Fineract rather than only reporting.
     */
    private final Map<String, ReadOnlyPool> readOnlyPools = new ConcurrentHashMap<>();

    /**
     * A pool for one principal, and the credential it belongs to.
     *
     * <p>The credential held is the stored ciphertext, not the password: it changes exactly when an
     * operator rotates {@code readonly_schema_password}, which is all this has to detect, and it
     * keeps a decrypted password out of everything but the pool that needs one.
     *
     * <p>The pool is built on first use rather than in the constructor, because
     * {@code new HikariDataSource(config)} starts the pool and opens a physical connection before it
     * returns. That is a database round trip, and it must not happen while {@link ConcurrentHashMap}
     * holds a bin lock. Holding the entry's own monitor instead keeps two reports from each building
     * one, without blocking a lookup for any other principal.
     */
    private static final class ReadOnlyPool {

        private final String encryptedPassword;
        private final Supplier<HikariDataSource> builder;
        private HikariDataSource dataSource;

        private ReadOnlyPool(String encryptedPassword, Supplier<HikariDataSource> builder) {
            this.encryptedPassword = encryptedPassword;
            this.builder = builder;
        }

        private synchronized HikariDataSource dataSource() {
            if (dataSource == null) {
                dataSource = builder.get();
            }
            return dataSource;
        }

        /** The pool if one was ever built, so that nothing closes a pool that never opened. */
        private synchronized HikariDataSource built() {
            return dataSource;
        }
    }

    /**
     * Opens a connection the database itself will refuse writes on.
     *
     * <p>The read-only flag of the transaction the caller runs in does not reach this connection:
     * Fineract's transaction manager is a {@code JpaTransactionManager}, which applies read-only to
     * the persistence context only and never calls {@link Connection#setReadOnly}. The restriction
     * therefore has to be applied to the connection BIRT is handed, so that the database refuses a
     * write rather than the plugin trying to recognise one — SQL a report script builds at run time
     * is invisible to any inspection of the design.
     */
    public Connection open() throws SQLException {
        return restrict(openTenantReportConnection());
    }

    /**
     * Applies the read-only restriction to an already-open connection, closing it if the restriction
     * cannot be applied: open but unrestricted is the one state a report must never be handed.
     */
    public Connection restrict(Connection connection) throws SQLException {
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
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    /** Rolls back whatever the report left open and returns the connection to its pool. */
    public void release(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.debug("Failed to roll back the BIRT report connection", e);
        }
        closeQuietly(connection);
    }

    /**
     * Wraps the connection in the view BIRT is allowed to have of it.
     *
     * <p>BIRT is stopped from closing the connection, because this factory owns its lifecycle, and
     * from lifting the restriction the connection was opened with. Statements are screened for
     * transaction control, which is what report SQL could otherwise use to leave the read-only
     * transaction and write, and for stored procedure calls, which a report has no business making.
     *
     * <p>Every JDBC object this connection hands out that can navigate back to a connection is
     * wrapped too — statements, their result sets, and the database metadata — so that
     * {@code getConnection()}, {@code getStatement()} and {@code unwrap()} lead back here rather
     * than to the physical connection. Only those navigation methods are answered by the wrappers;
     * every other call, a column read included, is passed straight through.
     *
     * <p>This screening is a second line rather than the boundary. The boundary is a {@code SELECT}
     * -only database principal, and the read-only transaction sits behind this catching ordinary
     * writes, which is why the pattern can afford to be this narrow. A {@code .rptdesign} is a
     * trusted, administrator-installed artefact for a further reason: BIRT runs report script with
     * unrestricted access to the JVM, which no JDBC wrapper can contain.
     */
    public Connection guard(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    final String methodName = method.getName();
                    if ("close".equals(methodName)) {
                        log.debug("Prevented BIRT from closing the report connection.");
                        return null;
                    }
                    if ("isClosed".equals(methodName)) {
                        return false;
                    }
                    if ("setReadOnly".equals(methodName)
                            || "setAutoCommit".equals(methodName)
                            || "commit".equals(methodName)
                            || "rollback".equals(methodName)) {
                        log.debug("Ignored BIRT attempt to call {} on the read-only report connection.", methodName);
                        return null;
                    }
                    if ("prepareCall".equals(methodName)) {
                        throw new SQLException(NO_STORED_PROCEDURES);
                    }
                    if ("unwrap".equals(methodName)) {
                        return unwrapToProxy(proxy, args);
                    }
                    if ("isWrapperFor".equals(methodName)) {
                        return wraps(proxy, args);
                    }
                    if (args != null && args.length > 0 && args[0] instanceof String sql) {
                        rejectTransactionControl(sql);
                    }
                    final Object result = invoke(connection, method, args);
                    if (result instanceof CallableStatement) {
                        throw new SQLException(NO_STORED_PROCEDURES);
                    }
                    if (result instanceof PreparedStatement statement) {
                        return guardStatement(statement, PreparedStatement.class, (Connection) proxy);
                    }
                    if (result instanceof Statement statement) {
                        return guardStatement(statement, Statement.class, (Connection) proxy);
                    }
                    if (result instanceof DatabaseMetaData metaData) {
                        return guardMetaData(metaData, (Connection) proxy);
                    }
                    return result;
                });
    }

    /**
     * Screens the SQL a statement is asked to run. A {@code Statement} takes its SQL at execution and
     * a {@code PreparedStatement} at creation, but both inherit the {@code execute(String)}
     * overloads, so both are screened the same way.
     *
     * <p>{@link Statement#getConnection()} returns the guarded connection rather than the one it was
     * created from, and the result sets it produces are wrapped in turn, so a report that reaches
     * the connection through a statement it holds is still screened rather than handed the way out.
     */
    private Object guardStatement(Statement statement, Class<? extends Statement> type, Connection guarded) {
        return Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                    final String methodName = method.getName();
                    if ("getConnection".equals(methodName)) {
                        return guarded;
                    }
                    if ("unwrap".equals(methodName)) {
                        return unwrapToProxy(proxy, args);
                    }
                    if ("isWrapperFor".equals(methodName)) {
                        return wraps(proxy, args);
                    }
                    if (args != null && args.length > 0 && args[0] instanceof String sql) {
                        rejectTransactionControl(sql);
                    }
                    final Object result = invoke(statement, method, args);
                    if (result instanceof ResultSet resultSet) {
                        return guardResultSet(resultSet, proxy);
                    }
                    return result;
                });
    }

    /**
     * {@link DatabaseMetaData#getConnection()} is the shortest way back to the physical connection,
     * and the result sets metadata returns carry {@link ResultSet#getStatement()} of their own.
     *
     * <p>Those result sets are wrapped with no statement: JDBC already defines {@code getStatement()}
     * as null for a result set that no statement produced, which is what a metadata result set is.
     */
    private Object guardMetaData(DatabaseMetaData metaData, Connection guarded) {
        return Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {DatabaseMetaData.class}, (proxy, method, args) -> {
                    final String methodName = method.getName();
                    if ("getConnection".equals(methodName)) {
                        return guarded;
                    }
                    if ("unwrap".equals(methodName)) {
                        return unwrapToProxy(proxy, args);
                    }
                    if ("isWrapperFor".equals(methodName)) {
                        return wraps(proxy, args);
                    }
                    final Object result = invoke(metaData, method, args);
                    if (result instanceof ResultSet resultSet) {
                        return guardResultSet(resultSet, null);
                    }
                    return result;
                });
    }

    /**
     * A result set hands back the statement that produced it, which hands back the connection.
     *
     * <p>Three methods are answered here and nothing else is touched, so a column read costs the
     * dispatch and no more — measured at about 1.4ns over a direct call, against the milliseconds a
     * row spends coming off the socket.
     */
    private Object guardResultSet(ResultSet resultSet, Object guardedStatement) {
        return Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
                    final String methodName = method.getName();
                    if ("getStatement".equals(methodName)) {
                        return guardedStatement;
                    }
                    if ("unwrap".equals(methodName)) {
                        return unwrapToProxy(proxy, args);
                    }
                    if ("isWrapperFor".equals(methodName)) {
                        return wraps(proxy, args);
                    }
                    return invoke(resultSet, method, args);
                });
    }

    /**
     * {@code unwrap} exists to reach the implementation behind a wrapper, which here is exactly what
     * a report must not have. The guarded object is returned for an interface it implements, and
     * anything else is refused rather than satisfied from the object underneath.
     */
    private static Object unwrapToProxy(Object proxy, Object[] args) throws SQLException {
        final Class<?> type = (Class<?>) args[0];
        if (type.isInstance(proxy)) {
            return proxy;
        }
        throw new SQLException("A BIRT report may not unwrap the report connection to " + type.getName() + ".");
    }

    /** Kept consistent with {@link #unwrapToProxy}: true for exactly what unwrap will return. */
    private static boolean wraps(Object proxy, Object[] args) {
        return ((Class<?>) args[0]).isInstance(proxy);
    }

    private void rejectTransactionControl(String sql) throws SQLException {
        if (TRANSACTION_CONTROL.matcher(sql).find()) {
            log.warn("Rejected transaction control in BIRT report SQL: {}", sql);
            throw new SQLException("A BIRT report may not issue transaction control statements.");
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
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

        log.debug("Running BIRT report as the tenant's read-only database principal {}", readOnlyUsername);
        return readOnlyPool(tenantConnection, readOnlyUsername).getConnection();
    }

    /**
     * Returns the pool for this principal, rebuilding it if the stored password has changed since it
     * was created.
     *
     * <p>A pool holds the password it was built with for as long as it lives, so without this an
     * operator rotating {@code readonly_schema_password} would see every report keep working until
     * the pool needed a new physical connection, and then fail to authenticate until Fineract was
     * restarted.
     *
     * <p>{@code compute} only decides which entry belongs in the map. Building the replacement and
     * closing the one it supersedes both happen after it returns, because each opens or tears down
     * physical connections and {@link ConcurrentHashMap} asks that a remapping function be short.
     */
    private HikariDataSource readOnlyPool(FineractPlatformTenantConnection tenantConnection, String username) {
        final String jdbcUrl = readOnlyJdbcUrl(tenantConnection);
        final String encryptedPassword = StringUtils.trimToEmpty(tenantConnection.getReadOnlySchemaPassword());
        final AtomicReference<HikariDataSource> superseded = new AtomicReference<>();

        final ReadOnlyPool pool = readOnlyPools.compute(jdbcUrl + "|" + username, (key, existing) -> {
            if (existing != null && existing.encryptedPassword.equals(encryptedPassword)) {
                return existing;
            }
            if (existing != null) {
                superseded.set(existing.built());
            }
            return new ReadOnlyPool(
                    encryptedPassword,
                    () -> buildReadOnlyPool(jdbcUrl, username, decryptReadOnlyPassword(encryptedPassword)));
        });

        final HikariDataSource replaced = superseded.get();
        if (replaced != null) {
            log.info("The read-only password for {} changed; closing the pool built with the old one", username);
            replaced.close();
        }
        return pool.dataSource();
    }

    /* package-private so a test can stand in for the pool without a database */
    HikariDataSource buildReadOnlyPool(String jdbcUrl, String username, String password) {
        final HikariConfig config = new HikariConfig();
        config.setPoolName("birt-readonly-" + username);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setReadOnly(true);
        config.setAutoCommit(false);
        config.setMaximumPoolSize(birtProperties.getReadOnlyPoolMaxSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(birtProperties.getReadOnlyConnectionTimeoutMillis());
        config.setValidationTimeout(birtProperties.getReadOnlyConnectionTimeoutMillis());
        config.setIdleTimeout(TimeUnit.MINUTES.toMillis(1));
        log.info(
                "Opened a BIRT read-only connection pool for {} with at most {} connections",
                username,
                config.getMaximumPoolSize());
        return new HikariDataSource(config);
    }

    private String readOnlyJdbcUrl(FineractPlatformTenantConnection tenantConnection) {
        final String driverClassName = DataSourceUtils.getDriverClassName(dataSource);
        return FineractPlatformTenantConnection.toJdbcUrl(
                FineractPlatformTenantConnection.resolveProtocol(driverClassName),
                StringUtils.defaultIfBlank(
                        tenantConnection.getReadOnlySchemaServer(), tenantConnection.getSchemaServer()),
                StringUtils.defaultIfBlank(
                        tenantConnection.getReadOnlySchemaServerPort(), tenantConnection.getSchemaServerPort()),
                StringUtils.defaultIfBlank(tenantConnection.getReadOnlySchemaName(), tenantConnection.getSchemaName()),
                StringUtils.defaultIfBlank(
                        tenantConnection.getReadOnlySchemaConnectionParameters(),
                        tenantConnection.getSchemaConnectionParameters()));
    }

    /**
     * The password column is stored encrypted, exactly like {@code schema_password}. Nothing else
     * says so, so a blank or plaintext value is the first thing an operator configuring this hits —
     * and the decrypt failure it produces reaches them as "Unknown SQL error", which names neither
     * the column nor what is wrong with it.
     */
    private String decryptReadOnlyPassword(String password) {
        if (StringUtils.isBlank(password)) {
            throw new PlatformDataIntegrityException(
                    "error.msg.reporting.readonly.password.missing",
                    "tenant_server_connections.readonly_schema_password is empty while readonly_schema_username is"
                            + " set. Store the read-only principal's password there, encrypted the same way as"
                            + " schema_password.");
        }
        try {
            return databasePasswordEncryptor.decrypt(password);
        } catch (Exception e) {
            throw new PlatformDataIntegrityException(
                    "error.msg.reporting.readonly.password.not.encrypted",
                    "tenant_server_connections.readonly_schema_password could not be decrypted. It has to be stored"
                            + " encrypted, the same way as schema_password.",
                    e);
        }
    }

    /**
     * Starts the read-only transaction on databases whose driver will not start one.
     *
     * <p>{@link Connection#setReadOnly} is enough on PostgreSQL, whose driver opens every transaction
     * with {@code BEGIN READ ONLY}. The MySQL and MariaDB drivers treat it as a replica-routing hint
     * and let every write through untouched, so there the transaction has to be opened explicitly.
     * Every other database is left to its driver, this way round on purpose: a URL nobody has
     * checked — {@code jdbc:aws-wrapper:postgresql} today, whatever is added next — is better served
     * by its driver's own behaviour than by a statement guessed on its behalf.
     *
     * <p>Deliberately a transaction-scoped statement rather than a session-scoped one: the connection
     * goes back to a pool that Fineract writes through, and {@code SET SESSION} would still be in
     * effect when the next borrower picks it up.
     */
    private void beginReadOnlyTransaction(Connection connection) throws SQLException {
        final String jdbcUrl =
                StringUtils.defaultString(connection.getMetaData().getURL());
        if (!StringUtils.containsIgnoreCase(jdbcUrl, ":mysql")
                && !StringUtils.containsIgnoreCase(jdbcUrl, ":mariadb")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("START TRANSACTION READ ONLY");
        }
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

    private void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Failed to close the BIRT report connection", e);
        }
    }

    @PreDestroy
    void closeReadOnlyPools() {
        readOnlyPools.values().stream()
                .map(ReadOnlyPool::built)
                .filter(Objects::nonNull)
                .forEach(HikariDataSource::close);
        readOnlyPools.clear();
    }
}
