/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.integration;

import static io.restassured.RestAssured.given;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("BIRT Report E2E Execution & Streaming Tests")
public class BirtReportExecutionIntegrationTest extends BirtIntegrationTestBase {

    @BeforeAll
    static void setupPermissions() {
        // Dynamically inject the dummy report and permissions so it doesn't crash the empty test DB
        String safeName = "Integration_Test_Report";
        String permissionCode = "READ_" + safeName.toUpperCase();

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

    @ParameterizedTest
    @CsvSource({
        "PDF, application/pdf",
        "HTML, text/html",
        "CSV, text/csv",
        "XLS, application/vnd.ms-excel",
        "XLSX, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    })
    @DisplayName("Should successfully stream Integration Test Report in all formats")
    void shouldStreamReportInAllFormatsSuccessfully(String outputType, String expectedContentType) {

        // Testcontainers maps Fineract's internal 8443 port to a random available host port
        String fineractBaseUrl = "https://" + FINERACT.getHost() + ":" + FINERACT.getMappedPort(8443);

        RequestSpecification requestSpec = new RequestSpecBuilder()
                .setBaseUri(fineractBaseUrl)
                .setRelaxedHTTPSValidation() // Bypasses self-signed SSL cert issues in Testcontainers
                .build();

        given().spec(requestSpec)
                .header("Fineract-Platform-TenantId", "default")
                .header("Authorization", "Basic bWlmb3M6cGFzc3dvcmQ=") // Translates to mifos:password
                .queryParam("tenantIdentifier", "default")
                .queryParam("output-type", outputType)
                .queryParam("locale", "en")
                .queryParam("dateFormat", "dd MMMM yyyy")
                .when()
                .get("/fineract-provider/api/v1/runreports/Integration_Test_Report")
                .then()
                .statusCode(200)
                .contentType(expectedContentType);
    }
}
