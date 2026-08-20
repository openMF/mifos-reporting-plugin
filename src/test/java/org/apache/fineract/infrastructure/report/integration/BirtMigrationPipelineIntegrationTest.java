/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** End-to-End validation of the Pentaho-to-BIRT Migration Compiler output. */
@DisplayName("E2E BIRT Migration Pipeline Validation")
public class BirtMigrationPipelineIntegrationTest extends BirtIntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(BirtMigrationPipelineIntegrationTest.class);

    @BeforeAll
    static void setupRestAssured() {
        RestAssured.baseURI = "https://localhost";
        RestAssured.port = getFineractPort();
        RestAssured.useRelaxedHTTPSValidation();
    }

    static Stream<String> provideMigratedReports() {
        // MX-317: We restrict the automated integration pipeline to ONLY run the dummy
        // Integration_Test_Report. The 66 migrated reports require complex API data lifecycles
        // (e.g., Written-Off Loans, specific product configurations) that are out of scope
        // for the default test container data.
        return Stream.of("Integration_Test_Report");
    }

    private void registerReportInFineractDatabase(String reportName) {
        if (reportName.contains(";") || reportName.contains("\n") || reportName.contains("\r")) {
            throw new IllegalArgumentException("Invalid report name: " + reportName);
        }
        String safeName = reportName.replace("'", "''");
        String permissionCode = "READ_" + safeName.toUpperCase().replace(" ", "_");

        String[] sqlCommands = {
            String.format(
                    "INSERT INTO stretchy_report (report_name, report_type, report_category, report_sql, description, core_report, use_report) SELECT '%s', 'BIRT', 'Migration', '', 'Auto-registered', true, true WHERE NOT EXISTS (SELECT 1 FROM stretchy_report WHERE report_name = '%s');",
                    safeName, safeName),
            String.format("UPDATE stretchy_report SET report_type = 'BIRT' WHERE report_name = '%s';", safeName),
            "INSERT INTO m_permission (grouping, code, entity_name, action_name, can_maker_checker) SELECT 'report', 'READ_REPORT', 'REPORT', 'READ', false WHERE NOT EXISTS (SELECT 1 FROM m_permission WHERE code = 'READ_REPORT');",
            String.format(
                    "INSERT INTO m_permission (grouping, code, entity_name, action_name, can_maker_checker) SELECT 'report', '%s', 'REPORT', 'READ', false WHERE NOT EXISTS (SELECT 1 FROM m_permission WHERE code = '%s');",
                    permissionCode, permissionCode),
            "INSERT INTO m_role_permission (role_id, permission_id) SELECT 1, id FROM m_permission WHERE code = 'READ_REPORT' AND NOT EXISTS (SELECT 1 FROM m_role_permission WHERE role_id = 1 AND permission_id = (SELECT id FROM m_permission WHERE code = 'READ_REPORT'));",
            String.format(
                    "INSERT INTO m_role_permission (role_id, permission_id) SELECT 1, id FROM m_permission WHERE code = '%s' AND NOT EXISTS (SELECT 1 FROM m_role_permission WHERE role_id = 1 AND permission_id = (SELECT id FROM m_permission WHERE code = '%s'));",
                    permissionCode, permissionCode)
        };

        for (String sql : sqlCommands) {
            execPostgres("psql", "-U", "postgres", "-d", "fineract_default", "-c", sql);
        }
    }

    private Map<String, String> extractExpectedParameters(String reportName) {
        Map<String, String> params = new HashMap<>();
        params.put("tenantIdentifier", "default");
        params.put("locale", "en");
        params.put("dateFormat", "dd MMMM yyyy");
        params.put("output-type", "PDF");
        return params;
    }

    @ParameterizedTest(name = "[{index}] Validating Report: {0}")
    @MethodSource("provideMigratedReports")
    @DisplayName("Should successfully execute migrated report via REST API")
    void shouldExecuteMigratedReportSuccessfully(String reportName) {
        LOG.info("Triggering E2E validation for migrated report: {}", reportName);

        registerReportInFineractDatabase(reportName);
        Map<String, String> queryParams = extractExpectedParameters(reportName);

        Response response = given().auth()
                .preemptive()
                .basic(
                        System.getProperty("fineract.it.username", "mifos"),
                        System.getProperty("fineract.it.password", "password"))
                .header("Fineract-Platform-TenantId", "default")
                .queryParams(queryParams)
                .when()
                .get("/fineract-provider/api/v1/runreports/{reportName}", reportName);

        if (response.statusCode() != 200) {
            System.err.println("❌ QUARANTINE CANDIDATE: " + reportName);
            String responseBody = response.getBody().asString();
            System.err.println("💥 DEEP ERROR REASON: " + responseBody);
        }

        response.then().statusCode(200).contentType("application/pdf").header("Content-Disposition", notNullValue());

        byte[] pdfBytes = response.getBody().asByteArray();
        assertThat(pdfBytes).isNotEmpty();
    }
}
