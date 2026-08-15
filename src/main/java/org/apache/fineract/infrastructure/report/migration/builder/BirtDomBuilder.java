/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.builder;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@Component
@Scope("prototype")
/** Foundational DOM builder for generating BIRT .rptdesign XML structures. */
public class BirtDomBuilder {

    private final Document document;
    private final Element reportRoot;

    /**
     * Initializes a secure XML Document and scaffolds the root BIRT report element.
     *
     * @throws ParserConfigurationException if the XML builder cannot be initialized safely
     */
    public BirtDomBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        // Apply strict XML parsing security to prevent XXE/SSRF vulnerabilities
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException e) {
            // JAXP property not supported by the underlying parser; ignore securely
        }

        DocumentBuilder builder = factory.newDocumentBuilder();

        this.document = builder.newDocument();
        this.document.setXmlStandalone(true);

        this.reportRoot = this.document.createElement("report");
        this.reportRoot.setAttribute("xmlns", "http://www.eclipse.org/birt/2005/design");
        this.reportRoot.setAttribute("version", "3.2.27");
        this.reportRoot.setAttribute("id", "1");

        this.document.appendChild(this.reportRoot);
    }

    /**
     * Exposes the mutable report DOM Document for callers to modify.
     *
     * @return the XML Document instance
     */
    public Document getDocument() {
        return document;
    }

    /**
     * Exposes the mutable root report Element for callers to append children.
     *
     * @return the <report> root Element
     */
    public Element getReportRoot() {
        return reportRoot;
    }

    /** Creates and appends a new child element to the given parent. */
    public Element appendElement(Element parent, String tagName) {
        Element element = document.createElement(tagName);
        parent.appendChild(element);
        return element;
    }

    /** Appends a standard BIRT <property name="...">value</property> node. */
    public Element appendProperty(Element parent, String name, String textContent) {
        Element prop = appendElement(parent, "property");
        prop.setAttribute("name", name);
        if (textContent != null && !textContent.isBlank()) {
            prop.setTextContent(textContent);
        }
        return prop;
    }

    /**
     * Injects the JDBC data source configuration into the DOM dynamically. Reuses the existing
     * data-sources container if it was already created.
     *
     * @param driverClass the JDBC driver class (e.g., org.postgresql.Driver)
     * @param jdbcUrl the environment-specific database URL
     * @param dbUser the database username
     * @param base64Password the encrypted base64 password
     */
    public void buildDataSource(String driverClass, String jdbcUrl, String dbUser, String base64Password) {
        NodeList existing = this.reportRoot.getElementsByTagName("data-sources");
        Element dataSources =
                existing.getLength() > 0 ? (Element) existing.item(0) : appendElement(this.reportRoot, "data-sources");

        Element odaDataSource = appendElement(dataSources, "oda-data-source");

        odaDataSource.setAttribute("extensionID", "org.eclipse.birt.report.data.oda.jdbc");
        odaDataSource.setAttribute("name", "Data Source");
        odaDataSource.setAttribute("id", "7");

        appendProperty(odaDataSource, "odaDriverClass", driverClass);
        appendProperty(odaDataSource, "odaURL", jdbcUrl);
        appendProperty(odaDataSource, "odaUser", dbUser);

        Element encryptedProp = appendElement(odaDataSource, "encrypted-property");
        encryptedProp.setAttribute("name", "odaPassword");
        encryptedProp.setAttribute("encryptionID", "base64");
        encryptedProp.setTextContent(base64Password);
    }
}
