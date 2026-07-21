/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.generator;

import java.util.List;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.apache.fineract.infrastructure.report.migration.model.PentahoParameter;
import org.apache.fineract.infrastructure.report.migration.model.PentahoReportModel;
import org.apache.fineract.infrastructure.report.migration.model.PentahoSqlDataset;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@ExtendWith(MockitoExtension.class)
class BirtDomBuilderTest {

    @Mock
    private PentahoSqlTranslator sqlTranslator;

    @InjectMocks
    private BirtDomBuilder domBuilder;

    private XPath xPath;

    @BeforeEach
    void setUp() {
        xPath = XPathFactory.newInstance().newXPath();
    }

    @Test
    void givenValidReportModel_whenBuildDocument_thenCreatesProperXmlDom() throws Exception {
        // Arrange
        PentahoParameter param = new PentahoParameter("branchId", "integer", true, "1", false, null);
        PentahoSqlDataset dataset = new PentahoSqlDataset("ActiveLoans", "SELECT * FROM dummy");
        PentahoReportModel model = new PentahoReportModel("TestReport", List.of(dataset), List.of(param), List.of());

        when(sqlTranslator.translateToPositional(anyString()))
                .thenReturn(new TranslatedQuery("SELECT * FROM dummy WHERE id = ?", List.of("branchId")));

        // Act
        Document document = domBuilder.buildReportDocument(model);

        // Assert - Root Node
        Element root = document.getDocumentElement();
        assertThat(root.getTagName()).isEqualTo("report");
        assertThat(root.getAttribute("version")).isEqualTo("3.2.27");

        // Assert - Parameters Node
        NodeList paramNodes = (NodeList) xPath.evaluate("//parameters/scalar-parameter", document, XPathConstants.NODESET);
        assertThat(paramNodes.getLength()).isEqualTo(1);
        Element scalarParam = (Element) paramNodes.item(0);
        assertThat(scalarParam.getAttribute("name")).isEqualTo("branchId");

        // Assert - Data Sources
        NodeList dsNodes = (NodeList) xPath.evaluate("//data-sources/oda-data-source", document, XPathConstants.NODESET);
        assertThat(dsNodes.getLength()).isEqualTo(1);
        Element dataSource = (Element) dsNodes.item(0);
        assertThat(dataSource.getAttribute("name")).isEqualTo("Data Source");

        // Assert - Data Sets and Positional Parameter mapping
        NodeList dataSetNodes = (NodeList) xPath.evaluate("//data-sets/oda-data-set", document, XPathConstants.NODESET);
        assertThat(dataSetNodes.getLength()).isEqualTo(1);
        Element dataSet = (Element) dataSetNodes.item(0);
        assertThat(dataSet.getAttribute("name")).isEqualTo("ActiveLoans");

        String queryText = (String) xPath.evaluate(".//xml-property[@name='queryText']/text()", dataSet, XPathConstants.STRING);
        assertThat(queryText).contains("SELECT * FROM dummy WHERE id = ?");

        String paramMappingName = (String) xPath.evaluate(".//list-property[@name='parameters']/structure/property[@name='paramName']/text()", dataSet, XPathConstants.STRING);
        assertThat(paramMappingName).isEqualTo("branchId");
    }
}