/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.builder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.fineract.infrastructure.report.migration.model.PentahoParameter;
import org.apache.fineract.infrastructure.report.migration.model.PentahoReportModel;
import org.apache.fineract.infrastructure.report.migration.model.PentahoSqlDataset;
import org.apache.fineract.infrastructure.report.migration.util.BirtDataTypeMapper;
import org.apache.fineract.infrastructure.report.migration.util.PentahoSqlTranslator;
import org.apache.fineract.infrastructure.report.migration.util.TranslatedQuery;
import org.w3c.dom.CDATASection;
import org.w3c.dom.Element;

/** Orchestrates the translation and injection of Pentaho IR models into the BIRT XML DOM. */
public class BirtReportAssembler {

  private final BirtDomBuilder domBuilder;
  private final AtomicInteger elementIdCounter = new AtomicInteger(100);

  public BirtReportAssembler(BirtDomBuilder domBuilder) {
    this.domBuilder = domBuilder;
  }

  public void assemble(PentahoReportModel reportModel) {
    if (reportModel == null) {
      return;
    }
    buildReportParameters(reportModel.parameters());
    buildDataSets(reportModel.datasets(), reportModel.parameters());
  }

  private void buildReportParameters(List<PentahoParameter> parameters) {
    if (parameters == null || parameters.isEmpty()) return;
    Element parametersNode = domBuilder.appendElement(domBuilder.getReportRoot(), "parameters");
    for (PentahoParameter param : parameters) {
      buildSingleParameter(parametersNode, param);
    }
  }

  private void buildSingleParameter(Element parametersNode, PentahoParameter param) {
    Element scalarParam = domBuilder.appendElement(parametersNode, "scalar-parameter");
    scalarParam.setAttribute("name", param.name());
    scalarParam.setAttribute("id", String.valueOf(elementIdCounter.getAndIncrement()));

    domBuilder.appendProperty(scalarParam, "valueType", "static");
    domBuilder.appendProperty(scalarParam, "dataType", BirtDataTypeMapper.mapType(param.type()));
    domBuilder.appendProperty(scalarParam, "paramType", "simple");
    domBuilder.appendProperty(scalarParam, "controlType", "text-box");

    if (param.isMandatory()) {
      domBuilder.appendProperty(scalarParam, "isRequired", "true");
    }

    if (param.defaultValue() != null && !param.defaultValue().isBlank()) {
      Element defaultList = domBuilder.appendElement(scalarParam, "simple-property-list");
      defaultList.setAttribute("name", "defaultValue");
      Element val = domBuilder.appendElement(defaultList, "value");
      val.setAttribute("type", "constant");
      val.setTextContent(param.defaultValue()); // Retain intentional whitespace
    }
  }

  private void buildDataSets(List<PentahoSqlDataset> datasets, List<PentahoParameter> parameters) {
    if (datasets == null || datasets.isEmpty()) return;
    Element dataSetsNode = domBuilder.appendElement(domBuilder.getReportRoot(), "data-sets");

    Map<String, PentahoParameter> paramMap =
        parameters == null
            ? Map.of()
            : parameters.stream()
                .collect(Collectors.toMap(PentahoParameter::name, p -> p, (p1, p2) -> p1));

    for (PentahoSqlDataset dataset : datasets) {
      buildSingleDataSet(dataSetsNode, dataset, paramMap);
    }
  }

  private void buildSingleDataSet(
      Element dataSetsNode, PentahoSqlDataset dataset, Map<String, PentahoParameter> paramMap) {
    Element odaDataSet = domBuilder.appendElement(dataSetsNode, "oda-data-set");
    odaDataSet.setAttribute(
        "extensionID", "org.eclipse.birt.report.data.oda.jdbc.JdbcSelectDataSet");
    odaDataSet.setAttribute("name", dataset.queryName());
    odaDataSet.setAttribute("id", String.valueOf(elementIdCounter.getAndIncrement()));
    domBuilder.appendProperty(odaDataSet, "dataSource", "Data Source");

    TranslatedQuery translated = PentahoSqlTranslator.translate(dataset.sqlQuery());
    Element queryText = domBuilder.appendElement(odaDataSet, "xml-property");
    queryText.setAttribute("name", "queryText");

    String sql = translated.sql();
    if (sql.contains("]]>")) {
      queryText.setTextContent(sql);
    } else {
      CDATASection cdata = domBuilder.getDocument().createCDATASection(sql);
      queryText.appendChild(cdata);
    }

    injectDatasetParameters(odaDataSet, dataset.queryName(), translated.parameterNames(), paramMap);
  }

  private void injectDatasetParameters(
      Element odaDataSet,
      String queryName,
      List<String> queryParams,
      Map<String, PentahoParameter> paramMap) {
    if (queryParams.isEmpty()) return;
    Element listProp = domBuilder.appendElement(odaDataSet, "list-property");
    listProp.setAttribute("name", "parameters");
    int position = 1;

    for (String paramName : queryParams) {
      PentahoParameter pInfo = paramMap.get(paramName);
      if (pInfo == null) {
        throw new IllegalStateException(
            String.format("Dataset '%s' references missing parameter '%s'", queryName, paramName));
      }

      Element structure = domBuilder.appendElement(listProp, "structure");
      domBuilder.appendProperty(structure, "name", paramName + "_" + position);
      domBuilder.appendProperty(structure, "paramName", paramName);
      domBuilder.appendProperty(structure, "dataType", BirtDataTypeMapper.mapType(pInfo.type()));
      domBuilder.appendProperty(structure, "position", String.valueOf(position));
      domBuilder.appendProperty(structure, "isInput", "true");
      domBuilder.appendProperty(structure, "isOutput", "false");
      position++;
    }
  }
}
