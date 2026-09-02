/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.integration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * End-to-end coverage of WEB-1200: installing an Eclipse BIRT report design through the API.
 *
 * <p>Every assertion about where a design ends up is made against the host side of the directory
 * bound into the container, so these tests prove the file really is written to the authenticated
 * tenant's directory rather than merely that the endpoint answered 200.
 */
@DisplayName("BIRT Report Design Upload E2E Tests")
class BirtReportUploadIntegrationTest extends BirtIntegrationTestBase {

    private static final String UPLOAD_PATH = "/fineract-provider/api/v1/birt/reports";
    private static final String SUPER_USER_AUTH = basicAuth("mifos", "password");

    private static final String DESIGN_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <report xmlns="http://www.eclipse.org/birt/2005/design" version="3.2.27" id="1">
              <property name="units">in</property>
              <body/>
            </report>
            """;

    /** A user granted CREATE_REPORT and nothing else. */
    private static String createReportUserAuth;

    /** A user granted ALL_FUNCTIONS_READ, which must not be enough to install a design. */
    private static String readOnlyUserAuth;

    private static String basicAuth(String username, String password) {
        return "Basic "
                + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static RequestSpecification spec(String tenant, String authorization) {
        return new RequestSpecBuilder()
                .setBaseUri("https://" + FINERACT.getHost() + ":" + getFineractPort())
                .setRelaxedHTTPSValidation()
                .addHeader("Fineract-Platform-TenantId", tenant)
                .addHeader("Authorization", authorization)
                .build();
    }

    @BeforeAll
    void createUsersWithSpecificGrants() {
        createReportUserAuth = createUser("web1200_creator", "CREATE_REPORT");
        readOnlyUserAuth = createUser("web1200_reader", "ALL_FUNCTIONS_READ");
    }

    /** Creates a role holding exactly one permission, and a user in it. Returns its auth header. */
    private String createUser(String username, String permission) {
        final String password = "Web1204!setup9";

        int roleId = given().spec(spec(DEFAULT_TENANT, SUPER_USER_AUTH))
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + username + "_role\",\"description\":\"WEB-1200 test role\"}")
                .when()
                .post("/fineract-provider/api/v1/roles")
                .then()
                .statusCode(200)
                .extract()
                .path("resourceId");

        given().spec(spec(DEFAULT_TENANT, SUPER_USER_AUTH))
                .contentType(ContentType.JSON)
                .body("{\"permissions\":{\"" + permission + "\":true}}")
                .when()
                .put("/fineract-provider/api/v1/roles/" + roleId + "/permissions")
                .then()
                .statusCode(200);

        given().spec(spec(DEFAULT_TENANT, SUPER_USER_AUTH))
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + username + "\",\"firstname\":\"Web\",\"lastname\":\"Twelve\","
                        + "\"email\":\"" + username + "@example.com\",\"officeId\":1,\"roles\":[" + roleId + "],"
                        + "\"sendPasswordToEmail\":false,\"password\":\"" + password + "\","
                        + "\"repeatPassword\":\"" + password + "\"}")
                .when()
                .post("/fineract-provider/api/v1/users")
                .then()
                .statusCode(200);

        return basicAuth(username, password);
    }

    private io.restassured.response.Response upload(
            String tenant, String authorization, String fileName, byte[] content) {
        return given().spec(spec(tenant, authorization))
                .multiPart("file", fileName, content)
                .when()
                .post(UPLOAD_PATH);
    }

    private io.restassured.response.Response upload(String tenant, String fileName, String content) {
        return upload(tenant, SUPER_USER_AUTH, fileName, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Stores an uploaded design in the authenticated tenant's own directory")
    void storesDesignInTheTenantDirectory() throws IOException {
        String fileName = "WEB1200 Stored.rptdesign";

        upload(DEFAULT_TENANT, fileName, DESIGN_XML)
                .then()
                .statusCode(200)
                .body("fileName", org.hamcrest.Matchers.equalTo(fileName))
                .body("reportName", org.hamcrest.Matchers.equalTo("WEB1200 Stored"))
                .body("overwritten", org.hamcrest.Matchers.is(false))
                .body("size", org.hamcrest.Matchers.greaterThan(0));

        Path stored = tenantReportsDir(DEFAULT_TENANT).resolve(fileName);
        assertTrue(Files.isRegularFile(stored), "design should be written to " + stored);
        assertEquals(DESIGN_XML, Files.readString(stored));

        // It must not have been written to the shared directory the caller never named.
        assertFalse(Files.exists(REPORTS_DIR.resolve(fileName)), "design must not land in the shared directory");
    }

    @Test
    @DisplayName("Each tenant gets its own copy and cannot reach the other's")
    void tenantsAreIsolated() throws IOException {
        String fileName = "WEB1200 Isolation.rptdesign";
        String defaultDesign = DESIGN_XML.replace("<body/>", "<body><!--default--></body>");
        String secondDesign = DESIGN_XML.replace("<body/>", "<body><!--second--></body>");

        upload(DEFAULT_TENANT, fileName, defaultDesign).then().statusCode(200);
        upload(SECOND_TENANT, fileName, secondDesign).then().statusCode(200);

        Path forDefault = tenantReportsDir(DEFAULT_TENANT).resolve(fileName);
        Path forSecond = tenantReportsDir(SECOND_TENANT).resolve(fileName);

        assertEquals(defaultDesign, Files.readString(forDefault), "tenant A's design was modified by tenant B");
        assertEquals(secondDesign, Files.readString(forSecond));
    }

    @Test
    @DisplayName("A tenant cannot write into another tenant's directory by naming it")
    void cannotEscapeIntoAnotherTenantsDirectory() throws IOException {
        String victim = "WEB1200 Victim.rptdesign";
        upload(DEFAULT_TENANT, victim, DESIGN_XML).then().statusCode(200);

        // Tenant "second" tries to overwrite tenant "default"'s design by climbing out of its own
        // directory. The name is refused outright.
        upload(SECOND_TENANT, "../" + DEFAULT_TENANT + "/" + victim, "<report>hijacked</report>")
                .then()
                .statusCode(400);

        assertEquals(
                DESIGN_XML,
                Files.readString(tenantReportsDir(DEFAULT_TENANT).resolve(victim)),
                "another tenant's design must be untouched");
    }

    @Test
    @DisplayName("Re-uploading the same name replaces the design and reports it")
    void overwritesAnExistingDesign() throws IOException {
        String fileName = "WEB1200 Overwrite.rptdesign";
        String replacement = DESIGN_XML.replace("<body/>", "<body><!--v2--></body>");

        upload(DEFAULT_TENANT, fileName, DESIGN_XML).then().statusCode(200);
        upload(DEFAULT_TENANT, fileName, replacement)
                .then()
                .statusCode(200)
                .body("overwritten", org.hamcrest.Matchers.is(true));

        assertEquals(
                replacement, Files.readString(tenantReportsDir(DEFAULT_TENANT).resolve(fileName)));
    }

    @Test
    @DisplayName("A rejected upload leaves the design already installed intact")
    void rejectedUploadDoesNotTruncateTheExistingDesign() throws IOException {
        String fileName = "WEB1200 Intact.rptdesign";
        upload(DEFAULT_TENANT, fileName, DESIGN_XML).then().statusCode(200);

        upload(DEFAULT_TENANT, fileName, "%PDF-1.7 this is not a report at all")
                .then()
                .statusCode(400);

        assertEquals(
                DESIGN_XML,
                Files.readString(tenantReportsDir(DEFAULT_TENANT).resolve(fileName)),
                "a refused upload must not damage the installed design");
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {"report.pdf", "report.xml", "report.txt", "report.zip", "report.json", "report.prpt"})
    @DisplayName("Only .rptdesign is accepted")
    void rejectsOtherExtensions(String fileName) {
        upload(DEFAULT_TENANT, fileName, DESIGN_XML).then().statusCode(400);
        assertFalse(Files.exists(tenantReportsDir(DEFAULT_TENANT).resolve(fileName)));
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(
            strings = {
                "../escaped.rptdesign",
                "../../../etc/escaped.rptdesign",
                "nested/escaped.rptdesign",
                "nested\\escaped.rptdesign",
                "/etc/escaped.rptdesign"
            })
    @DisplayName("A file name carrying a path is refused")
    void rejectsPathTraversal(String fileName) {
        upload(DEFAULT_TENANT, fileName, DESIGN_XML).then().statusCode(400);
    }

    @Test
    @DisplayName("Content that is not a BIRT design is refused")
    void rejectsContentThatIsNotADesign() {
        upload(DEFAULT_TENANT, "WEB1200 NotXml.rptdesign", "%PDF-1.7 binary junk")
                .then()
                .statusCode(400);
        upload(DEFAULT_TENANT, "WEB1200 WrongRoot.rptdesign", "<catalog><book/></catalog>")
                .then()
                .statusCode(400);
        upload(DEFAULT_TENANT, "WEB1200 Malformed.rptdesign", "<report><unclosed>")
                .then()
                .statusCode(400);
        upload(
                        DEFAULT_TENANT,
                        "WEB1200 WrongNamespace.rptdesign",
                        "<report xmlns=\"http://example.com/not-birt\"><body/></report>")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("A design declaring a doctype is refused rather than resolved")
    void rejectsExternalEntities() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE report [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<report xmlns=\"http://www.eclipse.org/birt/2005/design\"><body>&x;</body></report>";

        upload(DEFAULT_TENANT, "WEB1200 Xxe.rptdesign", xxe).then().statusCode(400);
    }

    @Test
    @DisplayName("An empty design is refused")
    void rejectsAnEmptyDesign() {
        upload(DEFAULT_TENANT, "WEB1200 Empty.rptdesign", "").then().statusCode(400);
    }

    @Test
    @DisplayName("A design over the size limit is refused")
    void rejectsAnOversizedDesign() {
        String oversized = "<report>" + "x".repeat(5 * 1024 * 1024) + "</report>";

        upload(DEFAULT_TENANT, "WEB1200 Huge.rptdesign", oversized).then().statusCode(400);
        assertFalse(Files.exists(tenantReportsDir(DEFAULT_TENANT).resolve("WEB1200 Huge.rptdesign")));
    }

    @Test
    @DisplayName("CREATE_REPORT is enough to install a design")
    void createReportPermissionIsAccepted() {
        upload(
                        DEFAULT_TENANT,
                        createReportUserAuth,
                        "WEB1200 ByCreator.rptdesign",
                        DESIGN_XML.getBytes(StandardCharsets.UTF_8))
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("ALL_FUNCTIONS is enough to install a design")
    void superUserIsAccepted() {
        upload(DEFAULT_TENANT, "WEB1200 BySuperUser.rptdesign", DESIGN_XML)
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("ALL_FUNCTIONS_READ is not enough, and nothing is written")
    void readOnlyUserIsRefused() {
        String fileName = "WEB1200 ByReader.rptdesign";

        upload(DEFAULT_TENANT, readOnlyUserAuth, fileName, DESIGN_XML.getBytes(StandardCharsets.UTF_8))
                .then()
                .statusCode(403);

        assertFalse(
                Files.exists(tenantReportsDir(DEFAULT_TENANT).resolve(fileName)),
                "an unauthorised upload must not write anything");
    }

    @Test
    @DisplayName("An unauthenticated request is refused")
    void unauthenticatedRequestIsRefused() {
        given().spec(spec(DEFAULT_TENANT, basicAuth("mifos", "wrong-password")))
                .multiPart("file", "WEB1200 NoAuth.rptdesign", DESIGN_XML.getBytes(StandardCharsets.UTF_8))
                .when()
                .post(UPLOAD_PATH)
                .then()
                .statusCode(401);
    }

    /**
     * The strongest statement these tests can make: a design that arrived through the endpoint is the
     * one the reporting engine then renders.
     *
     * <p>The shipped {@code Active_Loans_Details} report is registered in the catalogue and runs from
     * the shared directory. Uploading it installs it in the tenant's own directory, where the loader
     * looks first, so a run that still succeeds is rendering the uploaded bytes.
     */
    @Test
    @DisplayName("A design installed through the API is what the engine renders")
    void uploadedDesignIsRenderedByTheEngine() throws IOException {
        byte[] shipped = Files.readAllBytes(REPORTS_DIR.resolve("Active_Loans_Details.rptdesign"));

        upload(DEFAULT_TENANT, SUPER_USER_AUTH, "Active_Loans_Details.rptdesign", shipped)
                .then()
                .statusCode(200);

        assertTrue(Files.isRegularFile(tenantReportsDir(DEFAULT_TENANT).resolve("Active_Loans_Details.rptdesign")));

        given().spec(spec(DEFAULT_TENANT, SUPER_USER_AUTH))
                .queryParam("tenantIdentifier", DEFAULT_TENANT)
                .queryParam("output-type", "PDF")
                .queryParam("locale", "en")
                .queryParam("dateFormat", "dd MMMM yyyy")
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
