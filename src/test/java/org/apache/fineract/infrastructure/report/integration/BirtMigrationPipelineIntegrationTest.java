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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    static Stream<String> provideMigratedReports() throws IOException {
        Path reportsDir = Paths.get("birt", "reports");
        // FIX: Use try-with-resources to prevent file handle leaks, converting to a list before
        // streaming
        try (Stream<Path> paths = Files.walk(reportsDir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".rptdesign"))
                    .map(p -> p.getFileName().toString().replace(".rptdesign", ""))
                    .toList()
                    .stream();
        }
    }

    private void registerReportInFineractDatabase(String reportName) {
        // FIX: Basic SQL Injection prevention for the container execution
        if (reportName.contains(";") || reportName.contains("\n") || reportName.contains("\r")) {
            throw new IllegalArgumentException("Invalid report name: " + reportName);
        }
        String safeName = reportName.replace("'", "''");

        String insertSql = String.format(
                "INSERT INTO stretchy_report (report_name, report_type, report_category, report_sql, description, core_report, use_report) "
                        + "SELECT '%s', 'BIRT', 'Migration', '', 'Auto-registered by Integration Test', true, true "
                        + "WHERE NOT EXISTS (SELECT 1 FROM stretchy_report WHERE report_name = '%s');",
                safeName, safeName);

        String updateSql =
                String.format("UPDATE stretchy_report SET report_type = 'BIRT' WHERE report_name = '%s';", safeName);

        execPostgres("psql", "-U", "postgres", "-d", "fineract_default", "-c", insertSql);
        execPostgres("psql", "-U", "postgres", "-d", "fineract_default", "-c", updateSql);
    }

    private Map<String, String> extractExpectedParameters(String reportName) throws IOException {
        Map<String, String> params = new HashMap<>();

        params.put("tenantIdentifier", "default");
        params.put("locale", "en");
        params.put("dateFormat", "dd MMMM yyyy");
        params.put("output-type", "PDF");

        Path reportPath = Paths.get("birt", "reports", reportName + ".rptdesign");
        String content = Files.readString(reportPath);

        Matcher m = Pattern.compile("<scalar-parameter[^>]*name=\"([^\"]+)\"").matcher(content);
        while (m.find()) {
            String pName = m.group(1);
            String lowerName = pName.toLowerCase();

            String value;
            if (lowerName.contains("date")) {
                value = "01 January 2010";
            } else if (lowerName.contains("url")) {
                value = "https://localhost";
            } else if (lowerName.contains("hierarchy")) {
                value = ".";
            } else if (lowerName.contains("officer")
                    || lowerName.contains("purpose")
                    || lowerName.contains("product")
                    || lowerName.contains("fund")
                    || lowerName.contains("currency")) {
                value = "-1";
            } else {
                value = "1";
            }

            params.put("R_" + pName, value);
        }
        return params;
    }

    @ParameterizedTest(name = "[{index}] Validating Report: {0}")
    @MethodSource("provideMigratedReports")
    @DisplayName("Should successfully execute migrated report via REST API")
    void shouldExecuteMigratedReportSuccessfully(String reportName) throws IOException {
        LOG.info("Triggering E2E validation for migrated report: {}", reportName);

        registerReportInFineractDatabase(reportName);
        Map<String, String> queryParams = extractExpectedParameters(reportName);

        Response response = given().auth()
                .preemptive()
                // FIX: Use system properties instead of hardcoded credentials
                .basic(
                        System.getProperty("fineract.it.username", "mifos"),
                        System.getProperty("fineract.it.password", "password"))
                .header("Fineract-Platform-TenantId", "default")
                .queryParams(queryParams)
                .when()
                .get("/fineract-provider/api/v1/runreports/{reportName}", reportName);

        if (response.statusCode() != 200) {
            System.err.println("❌ QUARANTINE CANDIDATE: " + reportName);
            response.then().log().ifError();
        }

        response.then().statusCode(200).contentType("application/pdf").header("Content-Disposition", notNullValue());

        byte[] pdfBytes = response.getBody().asByteArray();

        // FIX: Using AssertJ instead of JUnit Assertions
        assertThat(pdfBytes).isNotEmpty();
    }
}
