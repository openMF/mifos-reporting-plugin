/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.report.service.KycReportRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Actual PostgreSQL migration and routing checks. An explicit test JDBC URL can replace Docker. */
class KycReportDatabaseIntegrationTest {
    private static PostgreSQLContainer<?> postgres;
    private static String url;
    private static String username;
    private static String password;
    private final List<String> schemas = new ArrayList<>();

    @BeforeAll
    static void database() {
        url = System.getProperty("kyc.test.jdbc-url");
        if (url == null) {
            postgres = new PostgreSQLContainer<>("postgres:15-alpine");
            postgres.start();
            url = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        } else {
            username = System.getProperty("kyc.test.jdbc-user", System.getProperty("user.name"));
            password = System.getProperty("kyc.test.jdbc-password", "");
        }
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    @AfterEach
    void cleanup() {
        ThreadLocalContextUtil.clearTenant();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        for (String schema : schemas) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private DataSource schema() {
        String schema = "web1419_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(url, username, password)).execute("CREATE SCHEMA " + schema);
        schemas.add(schema);
        return new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, username, password);
    }

    private void migrate(DataSource dataSource) throws Exception {
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/tenant/module/reporting/parts/003-kyc-pdf-reports.xml");
        liquibase.afterPropertiesSet();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void migrationSupportsFreshAndExistingRegistrationsAndRepeatedBoots(boolean existing) throws Exception {
        var ds = schema();
        var jdbc = new JdbcTemplate(ds);
        // Relevant columns/constraints from the pinned Fineract initial schema, with generated IDs.
        jdbc.execute(
                "CREATE TABLE stretchy_report(id serial PRIMARY KEY, report_name varchar(100) NOT NULL UNIQUE, report_type varchar(20) NOT NULL, report_category varchar(45), description text, core_report boolean DEFAULT false, use_report boolean DEFAULT false, self_service_user_report boolean NOT NULL DEFAULT false)");
        jdbc.execute(
                "CREATE TABLE stretchy_parameter(id serial PRIMARY KEY, parameter_name varchar(45) NOT NULL UNIQUE)");
        jdbc.execute(
                "CREATE TABLE stretchy_report_parameter(id serial PRIMARY KEY, report_id int NOT NULL REFERENCES stretchy_report(id), parameter_id int NOT NULL REFERENCES stretchy_parameter(id), report_parameter_name varchar(45))");
        jdbc.execute(
                "CREATE TABLE m_permission(id serial PRIMARY KEY, grouping varchar(45), code varchar(100) NOT NULL UNIQUE, entity_name varchar(100), action_name varchar(100), can_maker_checker boolean NOT NULL)");
        jdbc.execute("CREATE TABLE m_role_permission(role_id bigint, permission_id int REFERENCES m_permission(id))");
        jdbc.execute("INSERT INTO stretchy_parameter(parameter_name) VALUES ('DefaultClient')");
        jdbc.execute(
                "INSERT INTO stretchy_report(report_name,report_type,use_report) VALUES ('ExistingReport','BIRT',true)");
        if (existing) {
            jdbc.execute(
                    "INSERT INTO stretchy_report(id,report_name,report_type,use_report) VALUES (101,'KYCNaturalPerson','KYC',true),(102,'KYCLegalPerson','Pentaho',false)");
            jdbc.execute(
                    "INSERT INTO m_permission(code,entity_name,action_name,can_maker_checker) VALUES ('READ_KYCNaturalPerson','KYCNaturalPerson','READ',false)");
            jdbc.execute(
                    "INSERT INTO m_role_permission SELECT 9,id FROM m_permission WHERE code='READ_KYCNaturalPerson'");
            jdbc.execute(
                    "INSERT INTO stretchy_report_parameter(report_id,parameter_id,report_parameter_name) SELECT 101,id,'clientId' FROM stretchy_parameter");
        }
        migrate(ds);
        migrate(ds);
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM stretchy_report", Integer.class));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM stretchy_report WHERE report_name IN ('KYCNaturalPerson','KYCLegalPerson') AND report_type='BIRT' AND NOT self_service_user_report",
                        Integer.class));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM m_permission WHERE code IN ('READ_KYCNaturalPerson','READ_KYCLegalPerson')",
                        Integer.class));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM stretchy_report_parameter WHERE report_parameter_name='clientId'",
                        Integer.class));
        assertEquals(existing ? 1 : 0, jdbc.queryForObject("SELECT COUNT(*) FROM m_role_permission", Integer.class));
        if (existing) {
            assertEquals(
                    101,
                    jdbc.queryForObject(
                            "SELECT id FROM stretchy_report WHERE report_name='KYCNaturalPerson'", Integer.class));
            assertFalse(jdbc.queryForObject(
                    "SELECT use_report FROM stretchy_report WHERE report_name='KYCLegalPerson'", Boolean.class));
        }
    }

    @Test
    void optionalTablesAndClientIdsStayInsideTheSelectedTenant() {
        DataSource first = schema(), second = schema();
        for (DataSource ds : List.of(first, second)) {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute(
                    "CREATE TABLE x_registered_table(registered_table_name varchar(100),application_table_name varchar(100))");
        }
        var routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return ThreadLocalContextUtil.getTenant().getTenantIdentifier();
            }
        };
        routing.setTargetDataSources(Map.of("first", first, "second", second));
        routing.setLenientFallback(false);
        routing.afterPropertiesSet();
        var repository = new KycReportRepository(new JdbcTemplate(routing));
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "first", "First", "UTC", null));
        assertTrue(repository.latestVerification(7).isEmpty());
        var jdbc = new JdbcTemplate(first);
        jdbc.execute(
                "CREATE TABLE m_client_kyc_verification(id bigint,client_id bigint,kyc_status varchar(50),created_on_utc timestamp)");
        jdbc.execute(
                "INSERT INTO m_client_kyc_verification VALUES (1,7,'OLD','2026-01-01'),(2,7,'PENDING','2026-01-01'),(3,8,'OTHER_CLIENT','2026-01-02')");
        assertEquals("PENDING", repository.latestVerification(7).get("kyc_status"));
        jdbc.execute("INSERT INTO x_registered_table VALUES ('dt_kyc_validation','m_client')");
        assertTrue(repository.validation("dt_kyc_validation", 7).isEmpty());
        jdbc.execute("CREATE TABLE dt_kyc_validation(client_id bigint,validation_status varchar(50))");
        jdbc.execute("INSERT INTO dt_kyc_validation VALUES (7,'INCOMPLETE'),(8,'OTHER_CLIENT')");
        assertEquals("INCOMPLETE", repository.validation("dt_kyc_validation", 7).get("validation_status"));
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(2L, "second", "Second", "UTC", null));
        assertTrue(repository.latestVerification(7).isEmpty());
        assertTrue(repository.validation("dt_kyc_validation", 7).isEmpty());
    }
}
