/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabasePasswordEncryptor;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the connection restriction itself. What it looks like against a real database — including
 * which writes the database then refuses, and where the restriction stops — is in {@code
 * BirtConnectionReadOnlyIntegrationTest}, which drives this same class.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BirtReadOnlyConnectionFactory Tests")
class BirtReadOnlyConnectionFactoryTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private DatabasePasswordEncryptor databasePasswordEncryptor;

    private BirtPluginProperties birtProperties;
    private BirtReadOnlyConnectionFactory factory;

    private Connection connection;
    private Statement statement;
    private FineractPlatformTenantConnection tenantConnection;
    private MockedStatic<ThreadLocalContextUtil> mockedThreadLocalContextUtil;
    private MockedStatic<org.apache.fineract.infrastructure.report.util.DataSourceUtils> mockedDataSourceUtils;

    @BeforeEach
    void setUp() throws Exception {
        birtProperties = new BirtPluginProperties();
        factory = spy(new BirtReadOnlyConnectionFactory(dataSource, databasePasswordEncryptor, birtProperties));

        connection = mock(Connection.class, RETURNS_DEEP_STUBS);
        statement = mock(Statement.class);
        lenient()
                .when(connection.getMetaData().getURL())
                .thenReturn("jdbc:postgresql://localhost:5432/fineract_tenant");
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(dataSource.getConnection()).thenReturn(connection);

        final FineractPlatformTenant tenant = mock(FineractPlatformTenant.class);
        tenantConnection = mock(FineractPlatformTenantConnection.class);
        lenient().when(tenant.getConnection()).thenReturn(tenantConnection);

        mockedThreadLocalContextUtil = mockStatic(ThreadLocalContextUtil.class);
        mockedThreadLocalContextUtil.when(ThreadLocalContextUtil::getTenant).thenReturn(tenant);

        mockedDataSourceUtils = mockStatic(org.apache.fineract.infrastructure.report.util.DataSourceUtils.class);
        mockedDataSourceUtils
                .when(() -> org.apache.fineract.infrastructure.report.util.DataSourceUtils.getDriverClassName(any()))
                .thenReturn("org.postgresql.Driver");
    }

    @AfterEach
    void tearDown() {
        mockedThreadLocalContextUtil.close();
        mockedDataSourceUtils.close();
    }

    @Test
    @DisplayName("Should hand out a connection the database will not accept writes on")
    void shouldRestrictTheConnection() throws Exception {
        when(tenantConnection.getReadOnlySchemaUsername()).thenReturn("");

        assertSame(connection, factory.open());

        final InOrder inOrder = inOrder(connection, statement);
        inOrder.verify(connection).setReadOnly(true);
        inOrder.verify(connection).setAutoCommit(false);
        // pins the read-only state: a database stops accepting READ WRITE once a query has run
        inOrder.verify(statement).execute("SELECT 1");

        // PostgreSQL's driver opens the transaction read-only by itself
        verify(statement, never()).execute("START TRANSACTION READ ONLY");
    }

    @Test
    @DisplayName("Should open the read-only transaction explicitly on MySQL/MariaDB")
    void shouldStartReadOnlyTransactionExplicitlyOnMariaDb() throws Exception {
        when(tenantConnection.getReadOnlySchemaUsername()).thenReturn("");
        when(connection.getMetaData().getURL()).thenReturn("jdbc:mariadb://localhost:3306/fineract_tenant");

        factory.open();

        final InOrder inOrder = inOrder(connection, statement);
        inOrder.verify(connection).setAutoCommit(false);
        // MariaDB's driver treats setReadOnly as a routing hint, so the transaction is opened by hand
        inOrder.verify(statement).execute("START TRANSACTION READ ONLY");
        inOrder.verify(statement).execute("SELECT 1");
    }

    /**
     * The check is for the two drivers known to need the statement, not for everything that is not
     * PostgreSQL. A URL nobody has checked — {@code jdbc:aws-wrapper:postgresql} is one Fineract
     * already supports — gets its driver's behaviour rather than a statement guessed on its behalf.
     */
    @Test
    @DisplayName("Should leave an unrecognised database to its own driver")
    void shouldNotIssueTheStatementForAnUnrecognisedDatabase() throws Exception {
        when(tenantConnection.getReadOnlySchemaUsername()).thenReturn("");
        when(connection.getMetaData().getURL())
                .thenReturn("jdbc:aws-wrapper:postgresql://cluster.rds.amazonaws.com:5432/fineract_tenant");

        factory.open();

        verify(connection).setReadOnly(true);
        verify(statement, never()).execute("START TRANSACTION READ ONLY");
    }

    /** Open but unrestricted is the one state a report must never be handed. */
    @Test
    @DisplayName("Should close the connection when the restriction cannot be applied")
    void shouldCloseTheConnectionWhenItCannotBeRestricted() throws Exception {
        doThrow(new SQLException("connection closed")).when(connection).setReadOnly(true);

        assertThrows(SQLException.class, () -> factory.restrict(connection));

        verify(connection).close();
    }

    @Test
    @DisplayName("Should run reports through a pool for the tenant's read-only principal")
    void shouldUseReadOnlyPrincipalWhenConfigured() throws Exception {
        final HikariDataSource pool = mock(HikariDataSource.class);
        when(pool.getConnection()).thenReturn(connection);
        doReturn(pool).when(factory).buildReadOnlyPool(anyString(), anyString(), anyString());

        when(tenantConnection.getReadOnlySchemaUsername()).thenReturn("birt_ro");
        when(tenantConnection.getReadOnlySchemaPassword()).thenReturn("encrypted_ro ");
        when(tenantConnection.getReadOnlySchemaServer()).thenReturn("replica");
        when(tenantConnection.getReadOnlySchemaServerPort()).thenReturn("5432");
        when(tenantConnection.getReadOnlySchemaName()).thenReturn("fineract_default");
        when(databasePasswordEncryptor.decrypt("encrypted_ro")).thenReturn("ro_pass");

        factory.open();

        verify(factory).buildReadOnlyPool("jdbc:postgresql://replica:5432/fineract_default", "birt_ro", "ro_pass");
        verify(dataSource, never()).getConnection();
    }

    /**
     * The reason the pool exists. A connection opened per report leaves the database's own connection
     * limit as the only bound on how many reports can be in flight, and exhausting that takes down
     * all of Fineract rather than only reporting.
     */
    @Test
    @DisplayName("Should build the read-only pool once and reuse it")
    void shouldReuseTheReadOnlyPool() throws Exception {
        final HikariDataSource pool = mock(HikariDataSource.class);
        when(pool.getConnection()).thenReturn(connection);
        doReturn(pool).when(factory).buildReadOnlyPool(anyString(), anyString(), anyString());

        when(tenantConnection.getReadOnlySchemaUsername()).thenReturn("birt_ro");
        when(tenantConnection.getReadOnlySchemaPassword()).thenReturn("encrypted_ro");
        when(tenantConnection.getReadOnlySchemaName()).thenReturn("fineract_default");
        when(databasePasswordEncryptor.decrypt("encrypted_ro")).thenReturn("ro_pass");

        factory.open();
        factory.open();
        factory.open();

        verify(factory).buildReadOnlyPool(anyString(), anyString(), anyString());
    }

    /**
     * A pool holds the password it was built with for as long as it lives, so reusing one across a
     * rotation would keep reports working until the pool needed a new physical connection and then
     * fail to authenticate until Fineract was restarted.
     */
    @Test
    @DisplayName("Should replace and close the pool when the read-only password is rotated")
    void shouldReplaceTheReadOnlyPoolWhenThePasswordIsRotated() throws Exception {
        final HikariDataSource firstPool = mock(HikariDataSource.class);
        final HikariDataSource secondPool = mock(HikariDataSource.class);
        when(firstPool.getConnection()).thenReturn(connection);
        when(secondPool.getConnection()).thenReturn(connection);
        doReturn(firstPool, secondPool).when(factory).buildReadOnlyPool(anyString(), anyString(), anyString());

        when(tenantConnection.getReadOnlySchemaUsername()).thenReturn("birt_ro");
        when(tenantConnection.getReadOnlySchemaName()).thenReturn("fineract_default");
        when(tenantConnection.getReadOnlySchemaPassword()).thenReturn("encrypted_old", "encrypted_new");
        when(databasePasswordEncryptor.decrypt("encrypted_old")).thenReturn("old_pass");
        when(databasePasswordEncryptor.decrypt("encrypted_new")).thenReturn("new_pass");

        factory.open();
        factory.open();

        final InOrder inOrder = inOrder(factory);
        inOrder.verify(factory).buildReadOnlyPool(anyString(), eq("birt_ro"), eq("old_pass"));
        inOrder.verify(factory).buildReadOnlyPool(anyString(), eq("birt_ro"), eq("new_pass"));
        // the superseded pool is closed once the replacement is in the map, not before
        verify(firstPool).close();
        verify(secondPool, never()).close();
    }

    /** A pool the rotation replaced must not be left open holding connections nobody will use. */
    @Test
    @DisplayName("Should close every pool it built on shutdown")
    void shouldCloseTheReadOnlyPoolsOnShutdown() throws Exception {
        final HikariDataSource pool = mock(HikariDataSource.class);
        when(pool.getConnection()).thenReturn(connection);
        doReturn(pool).when(factory).buildReadOnlyPool(anyString(), anyString(), anyString());
        when(tenantConnection.getReadOnlySchemaUsername()).thenReturn("birt_ro");
        when(tenantConnection.getReadOnlySchemaName()).thenReturn("fineract_default");
        when(tenantConnection.getReadOnlySchemaPassword()).thenReturn("encrypted_ro");
        when(databasePasswordEncryptor.decrypt("encrypted_ro")).thenReturn("ro_pass");
        factory.open();

        factory.closeReadOnlyPools();

        verify(pool).close();
    }

    /**
     * The pool is built on first use rather than inside the map's remapping function, because
     * starting one opens a physical connection. A build that fails therefore has to leave an entry a
     * later report can retry, not a poisoned one.
     */
    @Test
    @DisplayName("Should retry a pool that failed to build, on the next report")
    void shouldRetryAPoolThatFailedToBuild() throws Exception {
        final HikariDataSource pool = mock(HikariDataSource.class);
        when(pool.getConnection()).thenReturn(connection);
        doThrow(new PlatformDataIntegrityException("error.msg.reporting.sql.error", "the database was down"))
                .doReturn(pool)
                .when(factory)
                .buildReadOnlyPool(anyString(), anyString(), anyString());
        configureReadOnlyPrincipal();

        assertThrows(PlatformDataIntegrityException.class, () -> factory.open());
        assertSame(connection, factory.open());

        verify(factory, times(2)).buildReadOnlyPool(anyString(), anyString(), anyString());
    }

    /** Nothing may close a pool that never opened, and shutdown must not build one to close it. */
    @Test
    @DisplayName("Should not close a pool that was never built")
    void shouldNotCloseAPoolThatWasNeverBuilt() {
        doThrow(new PlatformDataIntegrityException("error.msg.reporting.sql.error", "the database was down"))
                .when(factory)
                .buildReadOnlyPool(anyString(), anyString(), anyString());
        configureReadOnlyPrincipal();
        assertThrows(PlatformDataIntegrityException.class, () -> factory.open());

        assertDoesNotThrow(factory::closeReadOnlyPools);

        verify(factory, times(1)).buildReadOnlyPool(anyString(), anyString(), anyString());
    }

    private void configureReadOnlyPrincipal() {
        when(tenantConnection.getReadOnlySchemaUsername()).thenReturn("birt_ro");
        when(tenantConnection.getReadOnlySchemaName()).thenReturn("fineract_default");
        when(tenantConnection.getReadOnlySchemaPassword()).thenReturn("encrypted_ro");
        when(databasePasswordEncryptor.decrypt("encrypted_ro")).thenReturn("ro_pass");
    }

    @Test
    @DisplayName("Should fall back to the tenant pool when no read-only principal is configured")
    void shouldFallBackToTenantPoolWhenNoReadOnlyPrincipal() throws Exception {
        when(tenantConnection.getReadOnlySchemaUsername()).thenReturn("");

        factory.open();

        verify(dataSource).getConnection();
    }

    /**
     * Nothing documents that the column has to hold an encrypted value, so a blank or plaintext one
     * is the first thing an operator configuring this hits. Left to the decrypt failure it reaches
     * them as "Unknown SQL error", naming neither the column nor what is wrong with it.
     */
    @Test
    @DisplayName("Should name the column when the read-only password is missing")
    void shouldNameTheColumnWhenTheReadOnlyPasswordIsMissing() {
        when(tenantConnection.getReadOnlySchemaUsername()).thenReturn("birt_ro");
        when(tenantConnection.getReadOnlySchemaPassword()).thenReturn("  ");

        final PlatformDataIntegrityException ex = assertThrows(PlatformDataIntegrityException.class, factory::open);

        assertEquals("error.msg.reporting.readonly.password.missing", ex.getGlobalisationMessageCode());
    }

    @Test
    @DisplayName("Should name the column when the read-only password is not encrypted")
    void shouldNameTheColumnWhenTheReadOnlyPasswordIsNotEncrypted() {
        when(tenantConnection.getReadOnlySchemaUsername()).thenReturn("birt_ro");
        when(tenantConnection.getReadOnlySchemaPassword()).thenReturn("plaintext");
        when(databasePasswordEncryptor.decrypt("plaintext")).thenThrow(new IllegalArgumentException("bad padding"));

        final PlatformDataIntegrityException ex = assertThrows(PlatformDataIntegrityException.class, factory::open);

        assertEquals("error.msg.reporting.readonly.password.not.encrypted", ex.getGlobalisationMessageCode());
    }

    @Test
    @DisplayName("Should ignore BIRT's attempts to lift the restriction or close the connection")
    void shouldIgnoreBirtLiftingTheRestriction() throws Exception {
        final Connection guarded = factory.guard(connection);

        guarded.close();
        guarded.setReadOnly(false);
        guarded.setAutoCommit(true);
        guarded.commit();

        verify(connection, never()).close();
        verify(connection, never()).setReadOnly(false);
        verify(connection, never()).setAutoCommit(true);
        verify(connection, never()).commit();
    }

    @Test
    @DisplayName("Should refuse the SQL a report would use to leave the read-only transaction")
    void shouldRefuseTransactionControl() {
        final Connection guarded = factory.guard(connection);

        assertRefused(guarded, "COMMIT");
        assertRefused(guarded, "commit;");
        assertRefused(guarded, "ROLLBACK");
        assertRefused(guarded, "BEGIN");
        assertRefused(guarded, "START TRANSACTION READ WRITE");
        assertRefused(guarded, "SET TRANSACTION READ WRITE");
        assertRefused(guarded, "SET SESSION default_transaction_read_only = off");
    }

    @Test
    @DisplayName("Should refuse a stored procedure call outright")
    void shouldRefuseStoredProcedureCalls() {
        final Connection guarded = factory.guard(connection);

        assertThrows(SQLException.class, () -> guarded.prepareCall("{ call do_something() }"));
    }

    /** The screening must not catch the SQL reports legitimately run. */
    @Test
    @DisplayName("Should leave ordinary report SQL alone")
    void shouldLeaveOrdinaryReportSqlAlone() throws Exception {
        final Connection guarded = factory.guard(connection);

        guarded.prepareStatement("SELECT l.commit_date, o.beginning_balance FROM m_loan l JOIN m_office o ON true");

        verify(connection)
                .prepareStatement("SELECT l.commit_date, o.beginning_balance FROM m_loan l JOIN m_office o ON true");
    }

    /**
     * Without this, a report holding a statement could reach the unguarded connection through it and
     * commit, or start a read-write transaction of its own.
     */
    @Test
    @DisplayName("Should hand back the guarded connection from a statement, not the real one")
    void shouldGuardTheConnectionReachedThroughAStatement() throws Exception {
        final Connection guarded = factory.guard(connection);

        final Connection reached = guarded.createStatement().getConnection();

        assertSame(guarded, reached);
        reached.close();
        verify(connection, never()).close();
        assertThrows(SQLException.class, () -> reached.prepareStatement("COMMIT"));
    }

    /**
     * {@code DatabaseMetaData.getConnection()} is the shortest way back to the physical connection,
     * and BIRT reads metadata on every report.
     */
    @Test
    @DisplayName("Should hand back the guarded connection from database metadata")
    void shouldGuardTheConnectionReachedThroughMetaData() throws Exception {
        final Connection guarded = factory.guard(connection);

        final Connection reached = guarded.getMetaData().getConnection();

        assertSame(guarded, reached);
        reached.close();
        verify(connection, never()).close();
        assertThrows(SQLException.class, () -> reached.prepareStatement("COMMIT"));
    }

    /**
     * The full traversal a report can walk from the rows it is entitled to read:
     * {@code executeQuery(...).getStatement().getConnection()}.
     */
    @Test
    @DisplayName("Should hand back the guarded connection from a result set's statement")
    void shouldGuardTheConnectionReachedThroughAResultSet() throws Exception {
        final Connection guarded = factory.guard(connection);
        when(statement.executeQuery("SELECT 1")).thenReturn(mock(ResultSet.class));

        final ResultSet resultSet = guarded.createStatement().executeQuery("SELECT 1");
        final Connection reached = resultSet.getStatement().getConnection();

        assertSame(guarded, reached);
        reached.close();
        verify(connection, never()).close();
        assertThrows(SQLException.class, () -> reached.prepareStatement("COMMIT"));
    }

    /** A metadata result set was produced by no statement, which JDBC defines as null. */
    @Test
    @DisplayName("Should not hand back a statement from a metadata result set")
    void shouldGuardTheStatementReachedThroughAMetaDataResultSet() throws Exception {
        final Connection guarded = factory.guard(connection);
        final DatabaseMetaData metaData = guarded.getMetaData();
        final ResultSet tables = mock(ResultSet.class);
        // a driver is free to hand back an internal statement here, and that statement knows the
        // connection; lenient because the wrapper answering getStatement() is what leaves it unused
        lenient().when(tables.getStatement()).thenReturn(statement);
        when(connection.getMetaData().getTables(null, null, "%", null)).thenReturn(tables);

        assertNull(metaData.getTables(null, null, "%", null).getStatement());
    }

    /** unwrap exists to reach the implementation behind a wrapper, which is what must not leak. */
    @Test
    @DisplayName("Should refuse to unwrap any guarded object to the object underneath")
    void shouldRefuseToUnwrapToThePhysicalConnection() throws Exception {
        final Connection guarded = factory.guard(connection);
        when(statement.executeQuery("SELECT 1")).thenReturn(mock(ResultSet.class));
        final Statement guardedStatement = guarded.createStatement();
        final ResultSet guardedResultSet = guardedStatement.executeQuery("SELECT 1");

        assertSame(guarded, guarded.unwrap(Connection.class));
        assertSame(guardedStatement, guardedStatement.unwrap(Statement.class));
        assertSame(guardedResultSet, guardedResultSet.unwrap(ResultSet.class));
        assertSame(guarded, guarded.getMetaData().unwrap(DatabaseMetaData.class).getConnection());

        assertThrows(SQLException.class, () -> guarded.unwrap(PhysicalConnection.class));
        assertThrows(SQLException.class, () -> guardedStatement.unwrap(PhysicalConnection.class));
    }

    /** isWrapperFor has to agree with unwrap, or a caller is told to expect what it cannot get. */
    @Test
    @DisplayName("Should report isWrapperFor consistently with unwrap")
    void shouldKeepIsWrapperForConsistentWithUnwrap() throws Exception {
        final Connection guarded = factory.guard(connection);

        assertTrue(guarded.isWrapperFor(Connection.class));
        assertFalse(guarded.isWrapperFor(PhysicalConnection.class));
        assertTrue(guarded.createStatement().isWrapperFor(Statement.class));
        assertFalse(guarded.createStatement().isWrapperFor(PhysicalConnection.class));
    }

    /** Stands in for a driver's own connection interface, the thing unwrap is usually asked for. */
    private interface PhysicalConnection extends Connection {}

    private void assertRefused(Connection guarded, String sql) {
        assertThrows(SQLException.class, () -> guarded.prepareStatement(sql), "should refuse: " + sql);
    }
}
