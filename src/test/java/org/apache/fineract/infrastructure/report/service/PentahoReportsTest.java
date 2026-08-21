/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.infrastructure.report.service;

import com.google.common.truth.Truth;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor.Level;
import org.apache.fineract.client.util.Calls;
import org.apache.fineract.client.util.FineractClient;
import org.junit.jupiter.api.Test;
import retrofit2.Call;

/**
 * Integration Test for /runreports/ API with Pentaho plugin.
 *
 * @author Michael Vorburger.ch
 */
public class PentahoReportsTest {

  // This requires a locally running Fineract with this Pentaho Plugin.  The `./test` script does
  // that, see README.
  // (Later, it could be fully automated for this test to launch Fineract, e.g. using
  // https://github.com/vorburger/ch.vorburger.exec; but the problem is both fineract/ and this
  // fineract-pentaho/ need to be Gradle built BEFORE.)

  // based on https://github.com/apache/fineract/search?q=ReportsTest

  @Test
  void runExpectedPaymentsPentahoReport() {
    ResponseBody r =
        ok(
            fineract("default")
                .reportsRun
                .runReportGetFile(
                    "Expected Payments By Date - Formatted",
                    Map.of(
                        "tenantIdentifier",
                        "default",
                        "locale",
                        "en",
                        "dateFormat",
                        "dd MMMM yyyy",
                        "R_startDate",
                        "01 January 2022",
                        "R_endDate",
                        "02 January 2023",
                        "R_officeId",
                        "1",
                        "output-type",
                        "PDF",
                        "R_loanOfficerId",
                        "-1")));
    Truth.assertThat(r.contentType()).isEqualTo(MediaType.get("application/pdf"));
  }

  @Test
  void reportHonoursRequestedDateFormat() {
    ResponseBody r =
        ok(
            fineract("default")
                .reportsRun
                .runReportGetFile(
                    "Expected Payments By Date - Formatted",
                    Map.of(
                        "tenantIdentifier",
                        "default",
                        "locale",
                        "en",
                        "dateFormat",
                        "yyyy-MM-dd",
                        "R_startDate",
                        "2022-01-01",
                        "R_endDate",
                        "2023-01-02",
                        "R_officeId",
                        "1",
                        "output-type",
                        "PDF",
                        "R_loanOfficerId",
                        "-1")));
    Truth.assertThat(r.contentType()).isEqualTo(MediaType.get("application/pdf"));
  }

  /**
   * Verifies that a report is generated against the *requesting* tenant's database. The same report
   * is run for two different tenants; each must return that tenant's own data, so the (data) output
   * differs. Before the connection fix, {@code setConnectionDetail} was applied to a {@code
   * derive()} copy of the data factory, so both tenants received the first/embedded tenant's data
   * (identical output).
   *
   * <p>Requires the target Fineract to have two tenants with distinct client data. Override the
   * tenant identifiers with {@code -Dfineract.it.tenantA} / {@code -Dfineract.it.tenantB}.
   */
  @Test
  void reportUsesRequestingTenantDatabase() {
    String tenantA = System.getProperty("fineract.it.tenantA", "greenbank");
    String tenantB = System.getProperty("fineract.it.tenantB", "bluebank");

    String reportA = tenantData(runClientListing(tenantA));
    String reportB = tenantData(runClientListing(tenantB));

    Truth.assertThat(reportA).isNotEmpty();
    Truth.assertThat(reportB).isNotEmpty();
    Truth.assertThat(reportA).isNotEqualTo(reportB);
  }

  private String runClientListing(String tenant) {
    ResponseBody r =
        ok(
            fineract(tenant)
                .reportsRun
                .runReportGetFile(
                    "Client Listing(Pentaho)",
                    Map.of(
                        "tenantIdentifier", tenant,
                        "locale", "en",
                        "R_selectOffice", "1",
                        "output-type", "HTML")));
    try {
      return r.string();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // Strips HTML tags and the report's generation-time footer ("On: <date>") so the comparison
  // reflects the tenant's data rather than the timestamp (which always differs between runs).
  private static String tenantData(String html) {
    return html.replaceAll("<[^>]+>", " ")
        .replaceAll("(?s)On:.*", "")
        .replaceAll("\\s+", " ")
        .trim();
  }

  // ---
  // copy/paste from
  // fineract/integration-tests/src/test/java/org/apache/fineract/integrationtests/client/IntegrationTest.java
  // TODO move that from src/test to src/main publish an artifact, after
  // https://issues.apache.org/jira/browse/FINERACT-1102

  protected FineractClient fineract(String tenant) {
    String url =
        System.getProperty("fineract.it.url", "https://localhost:8443/fineract-provider/api/v1/");
    // insecure(true) should *ONLY* ever be used for https://localhost:8443, NOT in real clients!!
    return FineractClient.builder()
        .insecure(true)
        .baseURL(url)
        .tenant(tenant)
        .basicAuth("mifos", "password")
        .logging(Level.NONE)
        .build();
  }

  protected <T> T ok(Call<T> call) {
    return Calls.ok(call);
  }
}
