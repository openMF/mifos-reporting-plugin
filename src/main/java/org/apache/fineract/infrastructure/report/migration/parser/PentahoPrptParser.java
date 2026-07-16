/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.report.migration.model.PentahoParameter;
import org.apache.fineract.infrastructure.report.migration.model.PentahoReportModel;
import org.apache.fineract.infrastructure.report.migration.model.PentahoSqlDataset;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** MX-303, MX-304, MX-305: Parses Pentaho XML schemas into Intermediate Representation (IR). */
@Slf4j
@Component
@RequiredArgsConstructor
public class PentahoPrptParser {

  private final PentahoArchiveMemoryLoader memoryLoader;

  /**
   * Parses a Pentaho {@code .prpt} archive into the normalized {@link PentahoReportModel}.
   *
   * @param reportName name assigned to the resulting root report
   * @param prptStream input stream containing the PRPT ZIP archive
   * @return the parsed report model, including datasets, parameters, and subreports
   * @throws PentahoMigrationException if the archive cannot be loaded or parsed
   */
  public PentahoReportModel parseReport(String reportName, InputStream prptStream) {
    try {
      Map<String, String> archive = memoryLoader.loadArchive(prptStream);
      return parseRecursive(reportName, archive, "", new HashSet<>());
    } catch (ParserConfigurationException
        | SAXException
        | IOException
        | XPathExpressionException e) {
      throw new PentahoMigrationException(
          "Failed to parse Pentaho PRPT archive for report: " + reportName, e);
    }
  }

  private PentahoReportModel parseRecursive(
      String name, Map<String, String> archive, String basePath, Set<String> visitedPaths)
      throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
    if (!visitedPaths.add(basePath)) {
      log.warn(
          "Circular subreport reference detected at path: '{}'. Stopping recursion.", basePath);
      return new PentahoReportModel(name, List.of(), List.of(), List.of());
    }

    log.debug("Parsing PRPT layer at base path: '{}'", basePath);

    List<PentahoSqlDataset> datasets =
        parseDatasets(archive.get(basePath + "datasources/sql-ds.xml"));
    List<PentahoParameter> parameters =
        parseParameters(archive.get(basePath + "datadefinition.xml"));
    List<PentahoReportModel> subreports = parseSubreports(archive, basePath, visitedPaths);

    return new PentahoReportModel(name, datasets, parameters, subreports);
  }

  // CodeRabbit Fix: DRY XML Node Set Evaluation
  private NodeList evaluateNodeSet(String xml, String xpathExpr)
      throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
    Document doc = buildDocument(xml);
    XPath xpath = XPathFactory.newInstance().newXPath();
    return (NodeList) xpath.evaluate(xpathExpr, doc, XPathConstants.NODESET);
  }

  private List<PentahoSqlDataset> parseDatasets(String xml)
      throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
    List<PentahoSqlDataset> datasets = new ArrayList<>();
    if (StringUtils.isBlank(xml)) return datasets;

    NodeList queryNodes = evaluateNodeSet(xml, "//*[local-name()='query']");
    XPath xpath = XPathFactory.newInstance().newXPath(); // For internal sub-queries

    for (int i = 0; i < queryNodes.getLength(); i++) {
      datasets.add(toSqlDataset((Element) queryNodes.item(i), xpath));
    }
    return datasets;
  }

  private PentahoSqlDataset toSqlDataset(Element queryElement, XPath xpath)
      throws XPathExpressionException {
    String queryName = queryElement.getAttribute("name");
    // CodeRabbit Fix: Use string() to capture all text nodes and CDATA together
    String sql = xpath.evaluate("string(./*[local-name()='static-query'])", queryElement);
    return new PentahoSqlDataset(queryName, sql.trim());
  }

  private List<PentahoParameter> parseParameters(String xml)
      throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
    List<PentahoParameter> parameters = new ArrayList<>();
    if (StringUtils.isBlank(xml)) return parameters;

    NodeList paramNodes =
        evaluateNodeSet(
            xml, "//*[local-name()='plain-parameter' or local-name()='list-parameter']");
    for (int i = 0; i < paramNodes.getLength(); i++) {
      parameters.add(toParameter((Element) paramNodes.item(i)));
    }
    return parameters;
  }

  private PentahoParameter toParameter(Element paramElement) {
    String name = paramElement.getAttribute("name");
    String type = paramElement.getAttribute("type");
    boolean mandatory = "true".equalsIgnoreCase(paramElement.getAttribute("mandatory"));
    String defaultValue = paramElement.getAttribute("default-value");
    boolean isList = paramElement.getNodeName().endsWith("list-parameter");
    String queryName = isList ? paramElement.getAttribute("query") : null;
    return new PentahoParameter(name, type, mandatory, defaultValue, isList, queryName);
  }

  private List<PentahoReportModel> parseSubreports(
      Map<String, String> archive, String basePath, Set<String> visitedPaths)
      throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
    List<PentahoReportModel> subreports = new ArrayList<>();
    String layoutXml = archive.get(basePath + "layout.xml");
    if (StringUtils.isBlank(layoutXml)) return subreports;

    NodeList subreportNodes = evaluateNodeSet(layoutXml, "//*[local-name()='sub-report']");
    for (int i = 0; i < subreportNodes.getLength(); i++) {
      // CodeRabbit Fix: Safely unwrap Optional
      toSubreport((Element) subreportNodes.item(i), i, archive, basePath, visitedPaths)
          .ifPresent(subreports::add);
    }
    return subreports;
  }

  private Optional<PentahoReportModel> toSubreport(
      Element subElement,
      int index,
      Map<String, String> archive,
      String basePath,
      Set<String> visitedPaths)
      throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
    String href = subElement.getAttribute("href");
    if (StringUtils.isBlank(href)) return Optional.empty();

    String cleanHref = href.startsWith("/") ? href.substring(1) : href;
    int slashIdx = cleanHref.lastIndexOf("/");

    if (slashIdx < 0) {
      log.warn("Skipping subreport href without directory: '{}'", href);
      return Optional.empty();
    }

    // CodeRabbit Fix: Safely normalize directory traversal to correctly identify circular paths
    String targetDir = cleanHref.substring(0, slashIdx + 1);
    String newBasePath = Paths.get(basePath, targetDir).normalize().toString().replace("\\", "/");
    if (!newBasePath.isEmpty() && !newBasePath.endsWith("/")) {
      newBasePath += "/"; // Retain trailing slash for directory mapping
    }

    if (newBasePath.equals(basePath)) {
      log.warn("Skipping self-referencing subreport href: '{}'", href);
      return Optional.empty();
    }

    return Optional.of(
        parseRecursive("Subreport_" + index, archive, newBasePath, new HashSet<>(visitedPaths)));
  }

  private Document buildDocument(String xml)
      throws ParserConfigurationException, SAXException, IOException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);

    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);

    try {
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    } catch (IllegalArgumentException e) {
      log.debug(
          "JAXP properties ACCESS_EXTERNAL_DTD/SCHEMA not supported by the underlying XML parser. Ignoring.");
    }

    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }
}
