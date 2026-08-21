/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.core.persistence.ExtendedJpaTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.EclipseLinkJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Establishes what the JDBC connection BIRT executes a report's SQL on is actually allowed to do.
 *
 * <p>{@code BirtReportingProcessServiceImpl} runs every report inside a {@link TransactionTemplate}
 * marked read-only. These tests answer, against a real database and Apache Fineract's own
 * transaction manager, two questions that decide how the SQL embedded in a {@code .rptdesign} file
 * has to be contained:
 *
 * <ol>
 *   <li>does the transaction's read-only marker reach the JDBC connection? (it does not)
 *   <li>does the connection the service opens for BIRT make the database refuse the writes a report
 *       can issue? (it does, up to the boundary the last test records)
 * </ol>
 *
 * <p>This is why the plugin contains no SQL pattern matching: the report's SQL is never inspected,
 * it is executed somewhere ordinary writes fail. Report scripts can rewrite a query at run time, so
 * inspecting the design would not be sound anyway — and the one sequence that still gets through
 * would not be caught by a blacklist that a script can evade either.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("BIRT JDBC connection read-only behaviour")
public class BirtConnectionReadOnlyIntegrationTest {

    private static final String READ_ONLY_USER = "birt_ro";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("birt_readonly_probe")
            .withUsername("postgres")
            .withPassword("postgres");

    private HikariDataSource dataSource;
    private LocalContainerEntityManagerFactoryBean entityManagerFactoryBean;
    private ExtendedJpaTransactionManager transactionManager;

    @BeforeAll
    void startDatabase() throws SQLException {
        POSTGRES.start();

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setMaximumPoolSize(4);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE probe (id INT PRIMARY KEY, note TEXT)");
            statement.execute("INSERT INTO probe VALUES (1, 'seed')");
            statement.execute("CREATE ROLE " + READ_ONLY_USER + " LOGIN PASSWORD '" + READ_ONLY_USER + "'");
            statement.execute("GRANT CONNECT ON DATABASE " + POSTGRES.getDatabaseName() + " TO " + READ_ONLY_USER);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + READ_ONLY_USER);
            statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + READ_ONLY_USER);
        }

        entityManagerFactoryBean = buildEntityManagerFactory(dataSource);
        // false = the read-write manager, which is the one a report request runs under
        transactionManager = new ExtendedJpaTransactionManager(false);
        transactionManager.setEntityManagerFactory(entityManagerFactoryBean.getObject());
        transactionManager.setDataSource(dataSource);
        transactionManager.afterPropertiesSet();
    }

    @AfterAll
    void stopDatabase() {
        if (entityManagerFactoryBean != null) {
            entityManagerFactoryBean.destroy();
        }
        if (dataSource != null) {
            dataSource.close();
        }
        POSTGRES.stop();
    }

    /**
     * The premise the guard rests on. Apache Fineract's transaction manager is a
     * {@code JpaTransactionManager}: it applies read-only to the persistence context, never to the
     * JDBC connection. Should that ever change, this test fails and the guard becomes redundant
     * rather than wrong.
     */
    @Test
    @DisplayName("A read-only Spring transaction leaves the JDBC connection writable")
    void readOnlyTransactionDoesNotRestrictTheJdbcConnection() {
        final TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setReadOnly(true);

        final int rowsBefore = rowCount();

        transactionTemplate.execute(status -> {
            final Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                assertThat(connection.isReadOnly())
                        .as("read-only never reaches the connection BIRT would be handed")
                        .isFalse();

                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO probe VALUES (2, 'written in a read-only transaction')");
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
            return null;
        });

        assertThat(rowCount())
                .as("the write went through, so the transaction offered no protection")
                .isEqualTo(rowsBefore + 1);
    }

    @Test
    @DisplayName("The connection opened for BIRT lets the database reject every write")
    void readOnlyConnectionRejectsWrites() {
        assertRejected("INSERT INTO probe VALUES (99, 'injected')", "cannot execute INSERT in a read-only transaction");
        assertRejected("UPDATE probe SET note = 'tampered' WHERE id = 1", "cannot execute UPDATE");
        assertRejected("DELETE FROM probe", "cannot execute DELETE");
        assertRejected("CREATE TABLE injected (id INT)", "cannot execute CREATE TABLE");
    }

    /**
     * The property that makes the guard hold against hostile SQL rather than merely against honest
     * mistakes. A statement inside the report cannot hand itself write access back, because the
     * connection is opened with a query of our own and a database only pins read-only mode once the
     * transaction has taken its snapshot.
     */
    @Test
    @DisplayName("SQL inside the report cannot lift the read-only state")
    void readOnlyStateCannotBeLiftedBySql() {
        assertRejected("SET TRANSACTION READ WRITE", "must be set before any query");
        assertRejected("SELECT 1; INSERT INTO probe VALUES (98, 'stacked')", "cannot execute INSERT");
    }

    /** A report that commits, or changes the session default, still gets a read-only transaction. */
    @Test
    @DisplayName("Ending the transaction from SQL does not buy write access")
    void readOnlyStateSurvivesCommit() throws SQLException {
        try (Connection connection = openReadOnlyConnection()) {
            execute(connection, "SET default_transaction_read_only = off");
            execute(connection, "COMMIT");
            assertThatThrownBy(() -> execute(connection, "INSERT INTO probe VALUES (97, 'after commit')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("read-only transaction");
            connection.rollback();
        }
    }

    /**
     * The guard must not follow the connection back into the pool. Fineract writes through the same
     * pool, so a restriction left behind on a returned connection would break the next thing that
     * borrowed it — which is why the transaction, not the session, is what gets marked read-only.
     */
    @Test
    @DisplayName("A connection handed back to the pool is writable again")
    void poolConnectionIsNotLeftReadOnly() throws SQLException {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Connection connection = openReadOnlyConnection()) {
                connection.rollback();
            }

            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                assertThat(connection.isReadOnly()).isFalse();
                statement.execute("INSERT INTO probe VALUES (90, 'pool still writable')");
                statement.execute("DELETE FROM probe WHERE id = 90");
            }
        }
    }

    /**
     * The boundary a report cannot argue with, and the reason the tenant's read-only principal is
     * worth configuring: the same escape sequence that defeats the read-only transaction is refused
     * outright for a principal that was never granted the write.
     */
    @Test
    @DisplayName("A SELECT-only principal refuses the escape the read-only transaction cannot")
    void selectOnlyPrincipalRefusesTheEscape() throws SQLException {
        final int rowsBefore = rowCount();

        // the same sequence that defeats the read-only transaction, plus the writes it does catch
        assertRefusedForSelectOnlyPrincipal(
                "START TRANSACTION READ WRITE; INSERT INTO probe VALUES (94, 'escaped'); COMMIT");
        assertRefusedForSelectOnlyPrincipal("INSERT INTO probe VALUES (93, 'plain')");
        assertRefusedForSelectOnlyPrincipal("CREATE TABLE injected (id INT)");
        assertRefusedForSelectOnlyPrincipal("SET ROLE postgres");

        assertThat(rowCount()).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("A SELECT-only principal still serves the report's reads")
    void selectOnlyPrincipalStillReads() throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(POSTGRES.getJdbcUrl(), READ_ONLY_USER, READ_ONLY_USER);
                PreparedStatement statement = connection.prepareStatement("SELECT note FROM probe WHERE id = ?")) {
            statement.setInt(1, 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("seed");
            }
        }
    }

    /**
     * Records the boundary of the guard rather than leaving it to be discovered.
     *
     * <p>A report that opens a transaction of its own is no longer in the read-only one, and writes.
     * Nothing at the connection can prevent that — the statement is legal SQL and refusing it would
     * mean pattern matching the report's SQL, which does not survive scripts building queries at run
     * time. The answer to a hostile {@code .rptdesign} is a database user without write privileges,
     * plus keeping report installation an administrative operation.
     *
     * <p>If this test ever fails, the guard has become stronger than documented — check what changed
     * before relaxing the wording elsewhere.
     */
    @Test
    @DisplayName("Known limitation: a report that starts its own read-write transaction can write")
    void reportCanEscapeByStartingItsOwnTransaction() throws SQLException {
        final int rowsBefore = rowCount();

        try (Connection connection = openReadOnlyConnection()) {
            execute(connection, "COMMIT");
            execute(connection, "START TRANSACTION READ WRITE");
            execute(connection, "INSERT INTO probe VALUES (96, 'escaped')");
            execute(connection, "COMMIT");
        }

        assertThat(rowCount())
                .as("the escape is real; containment needs a read-only database user")
                .isEqualTo(rowsBefore + 1);
    }

    @Test
    @DisplayName("A read-only connection still serves the SELECT a report needs")
    void readOnlyConnectionStillReads() throws SQLException {
        try (Connection connection = openReadOnlyConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT note FROM probe WHERE id = ?")) {
            statement.setInt(1, 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("seed");
            }
            connection.rollback();
        }
    }

    /** Mirrors {@code BirtReportingProcessServiceImpl#openReadOnlyConnection}. */
    private Connection openReadOnlyConnection() throws SQLException {
        final Connection connection = dataSource.getConnection();
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        }
        return connection;
    }

    /**
     * Each statement gets its own connection: the first refusal aborts the transaction, so reusing
     * one would report "current transaction is aborted" for everything after it.
     */
    private void assertRejected(String sql, String expectedMessage) {
        assertThatThrownBy(() -> {
                    try (Connection connection = openReadOnlyConnection()) {
                        execute(connection, sql);
                    }
                })
                .as("the database should refuse: %s", sql)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(expectedMessage);
    }

    /** Fresh connection per statement: the first refusal aborts the transaction for the rest. */
    private void assertRefusedForSelectOnlyPrincipal(String sql) {
        assertThatThrownBy(() -> {
                    try (Connection connection =
                                    DriverManager.getConnection(POSTGRES.getJdbcUrl(), READ_ONLY_USER, READ_ONLY_USER);
                            Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as("a SELECT-only principal should be refused: %s", sql)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private int rowCount() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM probe")) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LocalContainerEntityManagerFactoryBean buildEntityManagerFactory(HikariDataSource dataSource) {
        final Map<String, Object> properties = new HashMap<>();
        properties.put("eclipselink.weaving", "false");
        properties.put("eclipselink.ddl-generation", "none");
        properties.put("eclipselink.logging.level", "OFF");

        final LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(dataSource);
        bean.setPersistenceUnitName("birt-readonly-probe");
        bean.setPackagesToScan("org.apache.fineract.infrastructure.report.integration.noentities");
        bean.setJpaVendorAdapter(new EclipseLinkJpaVendorAdapter());
        bean.setJpaPropertyMap(properties);
        bean.afterPropertiesSet();
        return bean;
    }
}
