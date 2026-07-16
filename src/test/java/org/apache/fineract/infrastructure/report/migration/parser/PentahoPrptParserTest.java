/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.report.migration.model.PentahoReportModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PentahoPrptParser TDD Tests")
class PentahoPrptParserTest {

  @Mock private PentahoArchiveMemoryLoader memoryLoader;

  @InjectMocks private PentahoPrptParser parser;

  @Test
  @DisplayName("Should successfully parse Pentaho datasets, parameters, and recursive subreports")
  void shouldParsePentahoReportModelSuccessfully() throws Exception {
    Map<String, String> mockArchive = new HashMap<>();

    String sqlXml =
        "<data:sql-datasource xmlns:data=\"http://jfreereport.sourceforge.net/namespaces/datasources/sql\">"
            + "<data:query-definitions><data:query name=\"Branch\"><data:static-query>select id from m_office</data:static-query></data:query></data:query-definitions></data:sql-datasource>";

    String paramXml =
        "<data-definition><parameter-definition><plain-parameter name=\"tenantUrl\" mandatory=\"true\" type=\"java.lang.String\" default-value=\"jdbc\"/></parameter-definition></data-definition>";

    String layoutXml =
        "<layout><report-footer><sub-report href=\"/subreport/content.xml\"/></report-footer></layout>";

    mockArchive.put("datasources/sql-ds.xml", sqlXml);
    mockArchive.put("datadefinition.xml", paramXml);
    mockArchive.put("layout.xml", layoutXml);
    mockArchive.put("subreport/datasources/sql-ds.xml", sqlXml);

    when(memoryLoader.loadArchive(any())).thenReturn(mockArchive);

    PentahoReportModel result =
        parser.parseReport("TestReport", new ByteArrayInputStream(new byte[0]));

    assertThat(result).isNotNull();
    assertThat(result.reportName()).isEqualTo("TestReport");

    assertThat(result.datasets()).hasSize(1);
    assertThat(result.datasets().get(0).queryName()).isEqualTo("Branch");
    assertThat(result.datasets().get(0).sqlQuery()).isEqualTo("select id from m_office");

    assertThat(result.parameters()).hasSize(1);
    assertThat(result.parameters().get(0).name()).isEqualTo("tenantUrl");
    assertThat(result.parameters().get(0).isMandatory()).isTrue();
    // CodeRabbit Fix: Assert type and default-value
    assertThat(result.parameters().get(0).type()).isEqualTo("java.lang.String");
    assertThat(result.parameters().get(0).defaultValue()).isEqualTo("jdbc");

    assertThat(result.subreports()).hasSize(1);
    assertThat(result.subreports().get(0).reportName()).isEqualTo("Subreport_0");
    assertThat(result.subreports().get(0).datasets().get(0).queryName()).isEqualTo("Branch");
  }

  @Test
  @DisplayName("Should parse list-parameter with query reference")
  void shouldParseListParameter() throws Exception {
    Map<String, String> mockArchive = new HashMap<>();
    String paramXml =
        "<data-definition><parameter-definition>"
            + "<list-parameter name=\"officeList\" mandatory=\"false\" type=\"java.lang.String\" "
            + "default-value=\"\" query=\"BranchQuery\"/>"
            + "</parameter-definition></data-definition>";

    mockArchive.put("datadefinition.xml", paramXml);
    when(memoryLoader.loadArchive(any())).thenReturn(mockArchive);

    PentahoReportModel result =
        parser.parseReport("TestReport", new ByteArrayInputStream(new byte[0]));

    assertThat(result.parameters()).hasSize(1);
    assertThat(result.parameters().get(0).name()).isEqualTo("officeList");
    assertThat(result.parameters().get(0).isList()).isTrue();
    assertThat(result.parameters().get(0).queryName()).isEqualTo("BranchQuery");
    assertThat(result.parameters().get(0).isMandatory()).isFalse();
  }

  @Test
  @DisplayName("Should gracefully skip circular subreports to prevent infinite loops")
  void shouldSkipCircularSubreports() throws Exception {
    Map<String, String> mockArchive = new HashMap<>();

    // Create a genuine circular loop by routing down into a folder,
    // then using '../' to route right back up to the parent.
    String parentLayoutXml =
        "<layout><report-footer><sub-report href=\"/child/content.xml\"/></report-footer></layout>";
    String childLayoutXml =
        "<layout><report-footer><sub-report href=\"../content.xml\"/></report-footer></layout>";

    mockArchive.put("layout.xml", parentLayoutXml);
    mockArchive.put("child/layout.xml", childLayoutXml);

    when(memoryLoader.loadArchive(any())).thenReturn(mockArchive);
    PentahoReportModel result =
        parser.parseReport("TestReport", new ByteArrayInputStream(new byte[0]));

    // The parent successfully loads the child
    assertThat(result.subreports()).hasSize(1);

    PentahoReportModel child = result.subreports().get(0);

    // The child tries to load the parent, hits the circular guard, and gets an empty dummy model
    // back
    assertThat(child.subreports()).hasSize(1);

    // Verify the blocked circular reference is an empty shell to stop recursion!
    PentahoReportModel blockedCircularRef = child.subreports().get(0);
    assertThat(blockedCircularRef.datasets()).isEmpty();
    assertThat(blockedCircularRef.parameters()).isEmpty();
    assertThat(blockedCircularRef.subreports()).isEmpty();
  }

  @Test
  @DisplayName("Should skip malformed subreport hrefs")
  void shouldSkipInvalidHrefs() throws Exception {
    Map<String, String> mockArchive = new HashMap<>();
    String badLayoutXml =
        "<layout><report-footer><sub-report href=\"badcontentxml\"/></report-footer></layout>";
    mockArchive.put("layout.xml", badLayoutXml);

    when(memoryLoader.loadArchive(any())).thenReturn(mockArchive);
    PentahoReportModel result =
        parser.parseReport("TestReport", new ByteArrayInputStream(new byte[0]));

    assertThat(result.subreports()).isEmpty();
  }

  @Test
  @DisplayName("Should wrap parsing exceptions inside PentahoMigrationException")
  void shouldWrapExceptions() throws Exception {
    when(memoryLoader.loadArchive(any())).thenThrow(new IOException("Simulated IO Error"));

    assertThatThrownBy(
            () -> parser.parseReport("TestReport", new ByteArrayInputStream(new byte[0])))
        .isInstanceOf(PentahoMigrationException.class)
        .hasMessageContaining("Failed to parse Pentaho PRPT archive for report: TestReport")
        .hasCauseInstanceOf(IOException.class);
  }
}
