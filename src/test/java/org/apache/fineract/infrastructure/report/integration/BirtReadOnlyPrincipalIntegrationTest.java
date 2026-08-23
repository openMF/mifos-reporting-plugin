/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.Container;

/**
 * Runs a shipped report end to end with the tenant's read-only database principal configured, the
 * arrangement that actually contains a hostile {@code .rptdesign}.
 *
 * <p>Point of the test: a principal granted only {@code SELECT} is worthless if BIRT cannot render
 * through it. This checks the report still comes back, that the plugin really connected as that
 * principal, and that the principal is one the database refuses writes from.
 */
@DisplayName("BIRT execution as the tenant's read-only principal")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BirtReadOnlyPrincipalIntegrationTest extends BirtIntegrationTestBase {

    private static final String READ_ONLY_USER = "birt_ro";

    /** A table the shipped report reads, used to take the principal's access away and put it back. */
    private static final String REPORT_TABLE = "m_loan";

    /**
     * {@code readonly_schema_password} is decrypted exactly like {@code schema_password}, so it has
     * to be stored the same way — a plaintext value there fails to decrypt and the report fails
     * closed. The role therefore gets the tenant's own database password and the column gets the
     * tenant's existing encrypted blob, which keeps the test free of the master password.
     */
    @BeforeAll
    void configureReadOnlyPrincipal() {
        run(
                "fineract_default",
                "CREATE ROLE " + READ_ONLY_USER + " LOGIN PASSWORD 'postgres';"
                        + "GRANT CONNECT ON DATABASE fineract_default TO " + READ_ONLY_USER + ";"
                        + "GRANT USAGE ON SCHEMA public TO " + READ_ONLY_USER + ";"
                        + "GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + READ_ONLY_USER + ";");

        run(
                "fineract_tenants",
                "UPDATE tenant_server_connections SET readonly_schema_server = 'db',"
                        + " readonly_schema_server_port = '5432',"
                        + " readonly_schema_name = 'fineract_default',"
                        + " readonly_schema_username = '" + READ_ONLY_USER + "',"
                        + " readonly_schema_password = schema_password;");
    }

    /**
     * The containers are static and shared, and Failsafe runs every integration test class in one
     * JVM. Left as it is, this class would quietly redirect every report run by every class after it
     * through {@code birt_ro}, and the first one that needs to write would fail somewhere unrelated.
     */
    @AfterAll
    void restoreTenantConnection() {
        run(
                "fineract_tenants",
                "UPDATE tenant_server_connections SET readonly_schema_server = NULL,"
                        + " readonly_schema_server_port = NULL,"
                        + " readonly_schema_name = NULL,"
                        + " readonly_schema_username = NULL,"
                        + " readonly_schema_password = NULL;");
    }

    @Test
    @Order(1)
    @DisplayName("Should render a shipped report through the SELECT-only principal")
    void shouldRenderReportAsReadOnlyPrincipal() {
        runReport().then().statusCode(200).contentType("text/csv");
    }

    /**
     * The test that makes the one above mean something.
     *
     * <p>A 200 and a CSV content type would come back just as happily if the plugin had ignored the
     * configured principal and used the ordinary tenant connection — which is the failure worth
     * catching. Taking {@code SELECT} away from {@code birt_ro} alone and watching the same request
     * start failing shows whose privileges the report is actually running under.
     */
    @Test
    @Order(2)
    @DisplayName("The report runs under the configured principal's privileges, not the tenant's")
    void reportUsesTheConfiguredPrincipalsPrivileges() {
        run("fineract_default", "REVOKE SELECT ON " + REPORT_TABLE + " FROM " + READ_ONLY_USER + ";");
        try {
            final Response response = runReport();
            assertThat(response.statusCode())
                    .as("removing birt_ro's SELECT must break the report, or it never ran as birt_ro")
                    .isNotEqualTo(200);
            assertThat(response.getBody().asString()).containsIgnoringCase("permission denied");
        } finally {
            run("fineract_default", "GRANT SELECT ON " + REPORT_TABLE + " TO " + READ_ONLY_USER + ";");
        }
    }

    /** Proves the principal the report ran as is genuinely unable to write. */
    @Test
    @Order(3)
    @DisplayName("The configured principal is refused every write by the database")
    void configuredPrincipalCannotWrite() {
        final Container.ExecResult result = execPostgres(
                "psql",
                "-U",
                READ_ONLY_USER,
                "-d",
                "fineract_default",
                "-c",
                "INSERT INTO m_office (id, name, hierarchy, opening_date) VALUES (999, 'evil', '.', now())");

        assertThat(result.getStderr()).contains("permission denied");
    }

    private Response runReport() {
        return given().spec(requestSpec())
                .header("Fineract-Platform-TenantId", "default")
                .header("Authorization", "Basic bWlmb3M6cGFzc3dvcmQ=")
                .queryParam("tenantIdentifier", "default")
                .queryParam("output-type", "CSV")
                .queryParam("locale", "en")
                .queryParam("dateFormat", "dd MMMM yyyy")
                .queryParam("R_branch", "1")
                .queryParam("R_loanOfficer", "-1")
                .queryParam("R_loanPurposeId", "-1")
                .queryParam("R_loanProductId", "-1")
                .queryParam("R_fundId", "-1")
                .queryParam("R_currencyId", "-1")
                .when()
                .get("/fineract-provider/api/v1/runreports/Active_Loans_Details");
    }

    private RequestSpecification requestSpec() {
        return new RequestSpecBuilder()
                .setBaseUri("https://" + FINERACT.getHost() + ":" + getFineractPort())
                .setRelaxedHTTPSValidation()
                .build();
    }

    private void run(String database, String sql) {
        final Container.ExecResult result = execPostgres("psql", "-U", "postgres", "-d", database, "-c", sql);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Setup SQL failed: " + result.getStderr());
        }
    }
}
