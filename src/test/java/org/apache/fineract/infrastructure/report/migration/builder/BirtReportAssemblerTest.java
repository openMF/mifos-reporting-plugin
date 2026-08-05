/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.apache.fineract.infrastructure.report.migration.model.PentahoParameter;
import org.apache.fineract.infrastructure.report.migration.model.PentahoReportModel;
import org.apache.fineract.infrastructure.report.migration.model.PentahoSqlDataset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;

@DisplayName("BirtReportAssembler Tests")
class BirtReportAssemblerTest {

  private BirtDomBuilder domBuilder;
  private BirtReportAssembler assembler;
  private XPath xpath;

  @BeforeEach
  void setUp() throws Exception {
    domBuilder = new BirtDomBuilder();
    assembler = new BirtReportAssembler(domBuilder);
    xpath = XPathFactory.newInstance().newXPath();
  }

  private Node getNode(String expression) throws XPathExpressionException {
    return (Node) xpath.evaluate(expression, domBuilder.getDocument(), XPathConstants.NODE);
  }

  private String getString(Node context, String expression) throws XPathExpressionException {
    return xpath.evaluate(expression, context);
  }

  @Test
  @DisplayName("Should assemble global report parameters and preserve whitespace")
  void shouldAssembleReportParameters() throws Exception {
    PentahoParameter param =
        new PentahoParameter("startDate", "java.util.Date", true, "  2023-01-01  ", false, null);
    assembler.assemble(new PentahoReportModel("Test", List.of(), List.of(param), List.of()));

    Node paramNode = getNode("/report/parameters/scalar-parameter[@name='startDate']");
    assertThat(paramNode).isNotNull();
    assertThat(getString(paramNode, "property[@name='dataType']/text()")).isEqualTo("date");
    assertThat(getString(paramNode, "property[@name='isRequired']/text()")).isEqualTo("true");
    assertThat(getString(paramNode, "simple-property-list[@name='defaultValue']/value/text()"))
        .isEqualTo("  2023-01-01  "); // Verifies whitespace is fully preserved
  }

  @Test
  @DisplayName("Should assemble datasets with distinct and repeated positional parameters")
  void shouldAssembleDataSets() throws Exception {
    setupComplexDatasetFixture();
    Node datasetNode = getNode("/report/data-sets/oda-data-set[@name='MainDS']");

    assertThat(datasetNode).isNotNull();
    assertThat(getString(datasetNode, "xml-property[@name='queryText']/text()"))
        .isEqualTo("SELECT * FROM offices WHERE id = ? AND status = ? OR parent_id = ?");

    assertBinding(datasetNode, 1, "officeId_1", "officeId", "integer");
    assertBinding(datasetNode, 2, "status_2", "status", "string");
    assertBinding(datasetNode, 3, "officeId_3", "officeId", "integer");
  }

  private void setupComplexDatasetFixture() {
    PentahoParameter p1 =
        new PentahoParameter("officeId", "java.lang.Integer", false, null, false, null);
    PentahoParameter p2 =
        new PentahoParameter("status", "java.lang.String", false, null, false, null);
    PentahoSqlDataset dataset =
        new PentahoSqlDataset(
            "MainDS",
            "SELECT * FROM offices WHERE id = ${officeId} AND status = ${status} OR parent_id = ${officeId}");
    assembler.assemble(
        new PentahoReportModel("TestReport", List.of(dataset), List.of(p1, p2), List.of()));
  }

  private void assertBinding(
      Node dsNode, int pos, String expectedName, String expectedParamName, String expectedType)
      throws Exception {
    Node struct =
        (Node)
            xpath.evaluate(
                "list-property[@name='parameters']/structure[" + pos + "]",
                dsNode,
                XPathConstants.NODE);
    assertThat(getString(struct, "property[@name='name']/text()")).isEqualTo(expectedName);
    assertThat(getString(struct, "property[@name='paramName']/text()"))
        .isEqualTo(expectedParamName);
    assertThat(getString(struct, "property[@name='dataType']/text()")).isEqualTo(expectedType);
    assertThat(getString(struct, "property[@name='position']/text()"))
        .isEqualTo(String.valueOf(pos));
  }

  @Test
  @DisplayName("Should reject dataset when referencing an undeclared report parameter")
  void shouldRejectMissingParameter() {
    PentahoSqlDataset ds =
        new PentahoSqlDataset("BadDS", "SELECT * FROM t WHERE id = ${missingId}");
    PentahoReportModel model = new PentahoReportModel("Test", List.of(ds), List.of(), List.of());

    assertThatThrownBy(() -> assembler.assemble(model))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Dataset 'BadDS' references missing parameter 'missingId'");
  }

  @Test
  @DisplayName("Should gracefully handle null models and empty lists")
  void shouldHandleNulls() {
    assembler.assemble(null);
    assembler.assemble(new PentahoReportModel("EmptyNulls", null, null, null));
    assembler.assemble(new PentahoReportModel("EmptyLists", List.of(), List.of(), List.of()));

    // Verify DOM builder remained completely intact and threw no errors
    assertThat(domBuilder.getDocument().getDocumentElement()).isNotNull();
  }
}
