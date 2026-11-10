/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.integration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

/** Full core /runreports dispatch, real report permissions and office/tenant boundaries. */
class KycReportExecutionIntegrationTest extends BirtIntegrationTestBase {
    private static final String API = "/fineract-provider/api/v1/";
    private static final String ADMIN = basic("mifos", "password");
    private int person;
    private int entity;
    private int secondPerson;
    private String reader;
    private String noReport;
    private String noClient;
    private String otherOffice;

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static RequestSpecification spec(String tenant, String auth) {
        return new RequestSpecBuilder()
                .setBaseUri("https://" + FINERACT.getHost() + ":" + getFineractPort())
                .setRelaxedHTTPSValidation()
                .addHeader("Fineract-Platform-TenantId", tenant)
                .addHeader("Authorization", auth)
                .build();
    }

    private int createClient(String tenant, boolean legalEntity, String name) {
        var body = new java.util.HashMap<String, Object>();
        body.put("officeId", 1);
        body.put("legalFormId", legalEntity ? 2 : 1);
        body.put("active", false);
        body.put("submittedOnDate", "01 January 2026");
        body.put("locale", "en");
        body.put("dateFormat", "dd MMMM yyyy");
        if (legalEntity) body.put("fullname", name);
        else {
            body.put("firstname", name);
            body.put("lastname", "WEB1419");
        }
        return given().spec(spec(tenant, ADMIN))
                .contentType(ContentType.JSON)
                .body(body)
                .post(API + "clients")
                .then()
                .statusCode(200)
                .extract()
                .path("clientId");
    }

    private String createUser(String name, int office, Map<String, Boolean> permissions) {
        int role = given().spec(spec(DEFAULT_TENANT, ADMIN))
                .contentType(ContentType.JSON)
                .body(Map.of("name", name, "description", "WEB1419 test"))
                .post(API + "roles")
                .then()
                .statusCode(200)
                .extract()
                .path("resourceId");
        given().spec(spec(DEFAULT_TENANT, ADMIN))
                .contentType(ContentType.JSON)
                .body(Map.of("permissions", permissions))
                .put(API + "roles/" + role + "/permissions")
                .then()
                .statusCode(200);
        String password = "Web1419!setup9";
        given().spec(spec(DEFAULT_TENANT, ADMIN))
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "username",
                        name,
                        "firstname",
                        "KYC",
                        "lastname",
                        "Test",
                        "email",
                        name + "@example.com",
                        "officeId",
                        office,
                        "roles",
                        new int[] {role},
                        "sendPasswordToEmail",
                        false,
                        "password",
                        password,
                        "repeatPassword",
                        password))
                .post(API + "users")
                .then()
                .statusCode(200);
        return basic(name, password);
    }

    @BeforeAll
    void fixtures() {
        person = createClient(DEFAULT_TENANT, false, "ScopedPerson");
        entity = createClient(DEFAULT_TENANT, true, "ScopedEntity");
        secondPerson = createClient(SECOND_TENANT, false, "SecondTenantPerson");
        reader = createUser(
                "web1419_reader",
                1,
                Map.of("READ_CLIENT", true, "READ_KYCNaturalPerson", true, "READ_KYCLegalPerson", true));
        noReport = createUser("web1419_no_report", 1, Map.of("READ_CLIENT", true));
        noClient = createUser("web1419_no_client", 1, Map.of("READ_KYCNaturalPerson", true));
        int office = given().spec(spec(DEFAULT_TENANT, ADMIN))
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "name",
                        "WEB1419 Branch",
                        "parentId",
                        1,
                        "openingDate",
                        "01 January 2020",
                        "dateFormat",
                        "dd MMMM yyyy",
                        "locale",
                        "en"))
                .post(API + "offices")
                .then()
                .statusCode(200)
                .extract()
                .path("officeId");
        otherOffice =
                createUser("web1419_other_office", office, Map.of("READ_CLIENT", true, "READ_KYCNaturalPerson", true));
    }

    private io.restassured.response.Response report(String tenant, String auth, String name, Object id) {
        return given().spec(spec(tenant, auth))
                .queryParam("R_clientId", id)
                .queryParam("output-type", "PDF")
                .get(API + "runreports/" + name);
    }

    private String pdfText(byte[] bytes) throws Exception {
        try (var pdf = new PdfReader(bytes)) {
            assertTrue(pdf.getNumberOfPages() > 0);
            var text = new StringBuilder();
            var extractor = new PdfTextExtractor(pdf);
            for (int i = 1; i <= pdf.getNumberOfPages(); i++) text.append(extractor.getTextFromPage(i));
            return text.toString();
        }
    }

    @Test
    void personAndEntityUseBirtDispatchAndDoNotInventChecks() throws Exception {
        for (var entry :
                Map.of("KYCNaturalPerson", person, "KYCLegalPerson", entity).entrySet()) {
            var response = report(DEFAULT_TENANT, reader, entry.getKey(), entry.getValue());
            response.then()
                    .statusCode(200)
                    .contentType("application/pdf")
                    .header("Cache-Control", "no-store, private")
                    .header("X-Content-Type-Options", "nosniff");
            String text = pdfText(response.asByteArray());
            assertTrue(text.contains(entry.getKey().equals("KYCNaturalPerson") ? "ScopedPerson" : "ScopedEntity"));
            assertTrue(text.contains("Not recorded"));
            assertTrue(text.contains("identity read permission is required"));
            assertTrue(text.contains("validation read permission is required"));
        }
    }

    @Test
    void permissionsAndOfficeScopeAreEnforcedByRealCoreServices() {
        report(DEFAULT_TENANT, noReport, "KYCNaturalPerson", person).then().statusCode(403);
        report(DEFAULT_TENANT, noClient, "KYCNaturalPerson", person).then().statusCode(403);
        report(DEFAULT_TENANT, otherOffice, "KYCNaturalPerson", person).then().statusCode(404);
    }

    @Test
    void rejectsWrongSelectionAndInvalidParameters() {
        report(DEFAULT_TENANT, ADMIN, "KYCLegalPerson", person).then().statusCode(400);
        report(DEFAULT_TENANT, ADMIN, "KYCNaturalPerson", entity).then().statusCode(400);
        report(DEFAULT_TENANT, ADMIN, "KYCNaturalPerson", "1 OR 1=1").then().statusCode(400);
        report(DEFAULT_TENANT, ADMIN, "KYCNaturalPerson", 0).then().statusCode(400);
        given().spec(spec(DEFAULT_TENANT, ADMIN))
                .queryParam("R_clientId", person)
                .queryParam("R_clientId", entity)
                .get(API + "runreports/KYCNaturalPerson")
                .then()
                .statusCode(400);
    }

    @Test
    void eachTenantReadsOnlyItsOwnClientRecords() throws Exception {
        String text = pdfText(report(SECOND_TENANT, ADMIN, "KYCNaturalPerson", secondPerson)
                .then()
                .statusCode(200)
                .extract()
                .asByteArray());
        assertTrue(text.contains("SecondTenantPerson"));
        assertFalse(text.contains("ScopedPerson"));
        String first = pdfText(report(DEFAULT_TENANT, ADMIN, "KYCNaturalPerson", person)
                .then()
                .statusCode(200)
                .extract()
                .asByteArray());
        assertTrue(first.contains("ScopedPerson"));
        assertFalse(first.contains("SecondTenantPerson"));
    }
}
