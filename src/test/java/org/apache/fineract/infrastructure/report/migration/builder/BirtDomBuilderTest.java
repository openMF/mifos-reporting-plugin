/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.builder;

import static org.assertj.core.api.Assertions.assertThat;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

@DisplayName("BirtDomBuilder Tests")
class BirtDomBuilderTest {

  private BirtDomBuilder builder;
  private XPath xpath;

  @BeforeEach
  void setUp() throws Exception {
    builder = new BirtDomBuilder();
    xpath = XPathFactory.newInstance().newXPath();
  }

  @Test
  @DisplayName("Should initialize valid Document with standard BIRT root node")
  void shouldInitializeRootNode() throws Exception {
    Document doc = builder.getDocument();
    assertThat(doc).isNotNull();

    Node root = (Node) xpath.evaluate("/report", doc, XPathConstants.NODE);
    assertThat(root).isNotNull();

    // Use DOM attribute access for the namespace declaration; XPath selection of `xmlns` is not
    // portable without namespace handling.
    Element rootElement = (Element) root;
    assertThat(rootElement.getAttribute("xmlns"))
        .isEqualTo("http://www.eclipse.org/birt/2005/design");

    assertThat(xpath.evaluate("/report/@version", doc)).isEqualTo("3.2.27");
    assertThat(xpath.evaluate("/report/@id", doc)).isEqualTo("1");
  }

  @Test
  @DisplayName("Should correctly append child elements")
  void shouldAppendElement() throws Exception {
    builder.appendElement(builder.getReportRoot(), "parameters");

    Node paramNode =
        (Node) xpath.evaluate("/report/parameters", builder.getDocument(), XPathConstants.NODE);
    assertThat(paramNode).isNotNull();
  }

  @Test
  @DisplayName("Should correctly append properties with attributes and text")
  void shouldAppendProperty() throws Exception {
    builder.appendProperty(builder.getReportRoot(), "createdBy", "Mifos Migration Tool");

    assertThat(xpath.evaluate("/report/property[@name='createdBy']/text()", builder.getDocument()))
        .isEqualTo("Mifos Migration Tool");
  }

  @Test
  @DisplayName("Should omit text content if property value is blank or null")
  void shouldOmitTextForBlankProperties() throws Exception {
    builder.appendProperty(builder.getReportRoot(), "emptyProp", null);
    builder.appendProperty(builder.getReportRoot(), "blankProp", "   ");

    assertThat(xpath.evaluate("/report/property[@name='emptyProp']/text()", builder.getDocument()))
        .isEmpty();
    assertThat(xpath.evaluate("/report/property[@name='blankProp']/text()", builder.getDocument()))
        .isEmpty();
  }

  @Test
  @DisplayName("Should build Fineract data-sources node dynamically from context")
  void shouldBuildDynamicDataSource() throws Exception {
    String testDriver = "org.postgresql.Driver";
    String testUrl = "jdbc:postgresql://localhost:5432/tenant_test_db";
    String testUser = "test_user";
    String testPass = "cGFzc3dvcmQ=";

    builder.buildDataSource(testDriver, testUrl, testUser, testPass);
    Document doc = builder.getDocument();

    // Verify datasource container
    Node dataSourceNode =
        (Node) xpath.evaluate("/report/data-sources/oda-data-source", doc, XPathConstants.NODE);
    assertThat(dataSourceNode).isNotNull();
    assertThat(xpath.evaluate("/report/data-sources/oda-data-source/@extensionID", doc))
        .isEqualTo("org.eclipse.birt.report.data.oda.jdbc");

    // Verify dynamic JDBC properties
    assertThat(
            xpath.evaluate(
                "/report/data-sources/oda-data-source/property[@name='odaDriverClass']/text()",
                doc))
        .isEqualTo(testDriver);
    assertThat(
            xpath.evaluate(
                "/report/data-sources/oda-data-source/property[@name='odaURL']/text()", doc))
        .isEqualTo(testUrl);
    assertThat(
            xpath.evaluate(
                "/report/data-sources/oda-data-source/property[@name='odaUser']/text()", doc))
        .isEqualTo(testUser);
    assertThat(
            xpath.evaluate(
                "/report/data-sources/oda-data-source/encrypted-property[@name='odaPassword']/text()",
                doc))
        .isEqualTo(testPass);
  }
}
