/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.generator;

import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.fineract.infrastructure.report.migration.model.PentahoParameter;
import org.apache.fineract.infrastructure.report.migration.model.PentahoReportModel;
import org.apache.fineract.infrastructure.report.migration.model.PentahoSqlDataset;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

@Component
public class BirtDomBuilder {

  private final PentahoSqlTranslator sqlTranslator;
  private final AtomicInteger idGenerator = new AtomicInteger(100); // Prevents ID collisions

  public BirtDomBuilder(PentahoSqlTranslator sqlTranslator) {
    this.sqlTranslator = sqlTranslator;
  }

  /** Programmatically generates a BIRT .rptdesign XML Document from the IR. */
  public Document buildReportDocument(PentahoReportModel pentahoModel)
      throws ParserConfigurationException {
    Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();

    // 1. Root <report> node
    Element root = document.createElement("report");
    root.setAttribute("xmlns", "http://www.eclipse.org/birt/2005/design");
    root.setAttribute("version", "3.2.27");
    root.setAttribute("id", "1");
    document.appendChild(root);

    // 2. Report Parameters
    if (pentahoModel.parameters() != null && !pentahoModel.parameters().isEmpty()) {
      Element paramsNode = appendNode(document, root, "parameters");
      for (PentahoParameter param : pentahoModel.parameters()) {
        buildScalarParameter(document, paramsNode, param);
      }
    }

    // 3. Data Sources (Hardcoded to Fineract standard tenant DB for generation)
    Element dataSourcesNode = appendNode(document, root, "data-sources");
    buildDefaultDataSource(document, dataSourcesNode);

    // 4. Data Sets
    if (pentahoModel.datasets() != null && !pentahoModel.datasets().isEmpty()) {
      Element dataSetsNode = appendNode(document, root, "data-sets");
      for (PentahoSqlDataset dataset : pentahoModel.datasets()) {
        buildDataset(document, dataSetsNode, dataset);
      }
    }

    return document;
  }

  private void buildScalarParameter(Document doc, Element parent, PentahoParameter param) {
    Element scalarNode = appendNode(doc, parent, "scalar-parameter");
    scalarNode.setAttribute("name", param.name());
    scalarNode.setAttribute("id", String.valueOf(idGenerator.incrementAndGet()));

    appendProperty(doc, scalarNode, "valueType", "static");
    appendProperty(doc, scalarNode, "dataType", mapPentahoTypeToBirt(param.type()));
    appendProperty(doc, scalarNode, "paramType", "simple");
    appendProperty(doc, scalarNode, "controlType", param.isList() ? "list-box" : "text-box");
    appendProperty(doc, scalarNode, "isRequired", String.valueOf(param.isMandatory()));
  }

  private void buildDefaultDataSource(Document doc, Element parent) {
    Element dsNode = appendNode(doc, parent, "oda-data-source");
    dsNode.setAttribute("extensionID", "org.eclipse.birt.report.data.oda.jdbc");
    dsNode.setAttribute("name", "Data Source");
    dsNode.setAttribute("id", "7");

    appendProperty(doc, dsNode, "odaDriverClass", "org.postgresql.Driver");
    appendProperty(doc, dsNode, "odaURL", "jdbc:postgresql://127.0.0.1:5432/fineract_default");
    appendProperty(doc, dsNode, "odaUser", "postgres");
  }

  private void buildDataset(Document doc, Element parent, PentahoSqlDataset dataset) {
    Element dsNode = appendNode(doc, parent, "oda-data-set");
    dsNode.setAttribute("extensionID", "org.eclipse.birt.report.data.oda.jdbc.JdbcSelectDataSet");
    dsNode.setAttribute("name", dataset.queryName());
    dsNode.setAttribute("id", String.valueOf(idGenerator.incrementAndGet()));

    appendProperty(doc, dsNode, "dataSource", "Data Source");

    // Translate the SQL and extract ordered parameters
    TranslatedQuery translatedQuery = sqlTranslator.translateToPositional(dataset.sqlQuery());

    Element queryTextNode = appendNode(doc, dsNode, "xml-property");
    queryTextNode.setAttribute("name", "queryText");
    queryTextNode.appendChild(doc.createCDATASection(translatedQuery.birtSqlQuery()));

    // Map the positional parameters dynamically
    if (!translatedQuery.orderedParameterNames().isEmpty()) {
      Element listPropNode = appendNode(doc, dsNode, "list-property");
      listPropNode.setAttribute("name", "parameters");

      int position = 1;
      for (String paramName : translatedQuery.orderedParameterNames()) {
        Element structNode = appendNode(doc, listPropNode, "structure");
        appendProperty(doc, structNode, "name", paramName);
        appendProperty(doc, structNode, "paramName", paramName);
        appendProperty(doc, structNode, "dataType", "string"); // Default fallback
        appendProperty(doc, structNode, "position", String.valueOf(position++));
        appendProperty(doc, structNode, "isInput", "true");
        appendProperty(doc, structNode, "isOutput", "false");
      }
    }
  }

  private String mapPentahoTypeToBirt(String pentahoType) {
    if (pentahoType == null) return "string";
    return switch (pentahoType.toLowerCase()) {
      case "numeric", "integer", "long" -> "integer";
      case "date" -> "date";
      case "decimal", "float", "double" -> "decimal";
      default -> "string";
    };
  }

  // --- DRY Elimination Helpers ---

  private Element appendNode(Document doc, Element parent, String tagName) {
    Element element = doc.createElement(tagName);
    parent.appendChild(element);
    return element;
  }

  private void appendProperty(
      Document doc, Element parent, String propertyName, String textContent) {
    Element property = doc.createElement("property");
    property.setAttribute("name", propertyName);
    property.setTextContent(textContent);
    parent.appendChild(property);
  }
}
