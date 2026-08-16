/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.builder;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.report.migration.model.PentahoParameter;
import org.apache.fineract.infrastructure.report.migration.model.PentahoReportModel;
import org.apache.fineract.infrastructure.report.migration.model.PentahoSqlDataset;
import org.apache.fineract.infrastructure.report.migration.util.BirtDataTypeMapper;
import org.apache.fineract.infrastructure.report.migration.util.PentahoSqlTranslator;
import org.apache.fineract.infrastructure.report.migration.util.TranslatedQuery;
import org.w3c.dom.CDATASection;
import org.w3c.dom.Element;

@Slf4j
public class BirtReportAssembler {

    private final BirtDomBuilder domBuilder;
    private final AtomicInteger elementIdCounter = new AtomicInteger(100);

    private Element parametersNode;
    private final Set<String> declaredParameters = new HashSet<>();

    public BirtReportAssembler(BirtDomBuilder domBuilder) {
        this.domBuilder = domBuilder;
    }

    public void assemble(PentahoReportModel reportModel) {
        if (reportModel == null) return;

        buildDataSources();
        buildReportParameters(reportModel.parameters());
        buildDataSets(reportModel.datasets(), reportModel.parameters());
        buildReportBody(reportModel.datasets());
    }

    private void buildDataSources() {
        Element dataSources = domBuilder.appendElement(domBuilder.getReportRoot(), "data-sources");
        Element ds = domBuilder.appendElement(dataSources, "oda-data-source");
        ds.setAttribute("extensionID", "org.eclipse.birt.report.data.oda.jdbc");
        ds.setAttribute("name", "Data Source");
        ds.setAttribute("id", String.valueOf(elementIdCounter.getAndIncrement()));
    }

    private void buildReportBody(List<PentahoSqlDataset> datasets) {
        if (datasets == null || datasets.isEmpty()) return;

        Element body = domBuilder.appendElement(domBuilder.getReportRoot(), "body");
        Element table = domBuilder.appendElement(body, "table");
        table.setAttribute("id", String.valueOf(elementIdCounter.getAndIncrement()));

        Element dataSetProp = domBuilder.appendElement(table, "property");
        dataSetProp.setAttribute("name", "dataSet");
        dataSetProp.setTextContent(datasets.get(0).queryName());

        // Inject a visible fallback detail row and cell, since Pentaho IR lacks strict result-column
        // metadata
        Element detail = domBuilder.appendElement(table, "detail");
        Element row = domBuilder.appendElement(detail, "row");
        Element cell = domBuilder.appendElement(row, "cell");
        Element label = domBuilder.appendElement(cell, "label");
        domBuilder.appendProperty(
                label,
                "text",
                "Dataset Placeholder: " + datasets.get(0).queryName() + " (Requires BIRT layout rendering)");
    }

    private void buildReportParameters(List<PentahoParameter> parameters) {
        parametersNode = domBuilder.appendElement(domBuilder.getReportRoot(), "parameters");
        if (parameters == null || parameters.isEmpty()) return;
        for (PentahoParameter param : parameters) {
            declaredParameters.add(param.name());
            buildSingleParameter(parametersNode, param);
        }
    }

    private boolean isSafeToExportDefault(String paramName) {
        if (paramName == null) return true;
        String pName = paramName.toLowerCase();
        return !(pName.contains("user")
                || pName.contains("pwd")
                || pName.contains("password")
                || pName.contains("url")
                || pName.contains("token")
                || pName.contains("secret")
                || pName.contains("connection"));
    }

    private void buildSingleParameter(Element parametersNode, PentahoParameter param) {
        Element scalarParam = domBuilder.appendElement(parametersNode, "scalar-parameter");
        scalarParam.setAttribute("name", param.name());
        scalarParam.setAttribute("id", String.valueOf(elementIdCounter.getAndIncrement()));

        String dataType = BirtDataTypeMapper.mapType(param.type());
        if ("integer".equalsIgnoreCase(dataType) || "decimal".equalsIgnoreCase(dataType)) {
            dataType = "string";
        }

        domBuilder.appendProperty(scalarParam, "valueType", "static");
        domBuilder.appendProperty(scalarParam, "dataType", dataType);
        domBuilder.appendProperty(scalarParam, "paramType", "simple");
        domBuilder.appendProperty(scalarParam, "controlType", "text-box");

        if (param.isMandatory()) {
            domBuilder.appendProperty(scalarParam, "isRequired", "true");
        }

        if (param.defaultValue() != null && !param.defaultValue().isBlank() && isSafeToExportDefault(param.name())) {
            Element defaultList = domBuilder.appendElement(scalarParam, "simple-property-list");
            defaultList.setAttribute("name", "defaultValue");
            Element val = domBuilder.appendElement(defaultList, "value");
            val.setAttribute("type", "constant");
            val.setTextContent(param.defaultValue());
        }
    }

    private void buildDataSets(List<PentahoSqlDataset> datasets, List<PentahoParameter> parameters) {
        if (datasets == null || datasets.isEmpty()) return;
        Element dataSetsNode = domBuilder.appendElement(domBuilder.getReportRoot(), "data-sets");

        Map<String, PentahoParameter> paramMap = parameters == null
                ? Map.of()
                : parameters.stream().collect(Collectors.toMap(PentahoParameter::name, p -> p, (p1, p2) -> p1));

        for (PentahoSqlDataset dataset : datasets) {
            buildSingleDataSet(dataSetsNode, dataset, paramMap);
        }
    }

    private void buildSingleDataSet(
            Element dataSetsNode, PentahoSqlDataset dataset, Map<String, PentahoParameter> paramMap) {
        Element odaDataSet = domBuilder.appendElement(dataSetsNode, "oda-data-set");
        odaDataSet.setAttribute("extensionID", "org.eclipse.birt.report.data.oda.jdbc.JdbcSelectDataSet");
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
            Element odaDataSet, String queryName, List<String> queryParams, Map<String, PentahoParameter> paramMap) {
        if (queryParams.isEmpty()) return;
        Element listProp = domBuilder.appendElement(odaDataSet, "list-property");
        listProp.setAttribute("name", "parameters");
        int position = 1;

        for (String paramName : queryParams) {
            PentahoParameter pInfo = paramMap.get(paramName);
            if (pInfo == null) {
                log.warn(
                        "Dataset '{}' references missing parameter '{}'. Adding dynamic fallback.",
                        queryName,
                        paramName);
                pInfo = new PentahoParameter(paramName, "string", false, null, false, null);

                // Inject missing parameter as a non-mandatory scalar-parameter declaration
                if (parametersNode != null && declaredParameters.add(paramName)) {
                    buildSingleParameter(parametersNode, pInfo);
                }
            }

            String mappedType = BirtDataTypeMapper.mapType(pInfo.type());
            if ("integer".equalsIgnoreCase(mappedType) || "decimal".equalsIgnoreCase(mappedType)) {
                mappedType = "string";
            }

            Element structure = domBuilder.appendElement(listProp, "structure");
            domBuilder.appendProperty(structure, "name", paramName + "_" + position);
            domBuilder.appendProperty(structure, "paramName", paramName);
            domBuilder.appendProperty(structure, "dataType", mappedType);
            domBuilder.appendProperty(structure, "position", String.valueOf(position));
            domBuilder.appendProperty(structure, "isInput", "true");
            domBuilder.appendProperty(structure, "isOutput", "false");
            position++;
        }
    }
}
