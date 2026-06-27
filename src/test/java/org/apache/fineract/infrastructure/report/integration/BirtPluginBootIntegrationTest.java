package org.apache.fineract.infrastructure.report.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.restassured.RestAssured;
import io.restassured.config.SSLConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BirtPluginBootIntegrationTest extends BirtIntegrationTestBase {

  @BeforeEach
  void setupRestAssured() {

    RestAssured.baseURI = "https://localhost";
    RestAssured.port = getFineractPort();

    RestAssured.config =
        RestAssured.config().sslConfig(SSLConfig.sslConfig().relaxedHTTPSValidation());
  }

  @Test
  @DisplayName("Apache Fineract should boot successfully with the BIRT plugin loaded")
  void fineractBootsSuccessfully() {

    given()
        .when()
        .get("/fineract-provider/actuator/health")
        .then()
        .statusCode(200)
        .body("status", equalTo("UP"));
  }
}
