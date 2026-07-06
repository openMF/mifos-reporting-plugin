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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BIRT Report E2E Execution Tests")
public class BirtReportExecutionIntegrationTest extends BirtIntegrationTestBase {

  @Test
  @DisplayName(
      "Should successfully generate Active Loans Details PDF report via Connection Injection")
  void shouldGeneratePdfReportSuccessfully() {

    // Testcontainers maps Fineract's internal 8443 port to a random available host port
    String fineractBaseUrl = "https://" + FINERACT.getHost() + ":" + FINERACT.getMappedPort(8443);

    RequestSpecification requestSpec =
        new RequestSpecBuilder()
            .setBaseUri(fineractBaseUrl)
            .setRelaxedHTTPSValidation() // Bypasses self-signed SSL cert issues in Testcontainers
            .build();

    given()
        .spec(requestSpec)
        .header("Fineract-Platform-TenantId", "default")
        .header("Authorization", "Basic bWlmb3M6cGFzc3dvcmQ=") // Translates to mifos:password
        .queryParam("tenantIdentifier", "default")
        .queryParam("output-type", "PDF")
        .queryParam("locale", "en")
        .queryParam("dateFormat", "dd MMMM yyyy")
        // Mapping the exact parameters registered in 001-enable-active-loans-report.xml
        .queryParam("R_branch", "1")
        .queryParam("R_loanOfficer", "-1")
        .queryParam("R_loanPurposeId", "-1")
        .queryParam("R_loanProductId", "-1")
        .queryParam("R_fundId", "-1")
        .queryParam("R_currencyId", "-1")
        .queryParam("R_startDate", "01 January 2020")
        .queryParam("R_endDate", "01 January 2030")
        .when()
        .get("/fineract-provider/api/v1/runreports/Active_Loans_Details")
        .then()
        .statusCode(200)
        .contentType("application/pdf");
  }
}
