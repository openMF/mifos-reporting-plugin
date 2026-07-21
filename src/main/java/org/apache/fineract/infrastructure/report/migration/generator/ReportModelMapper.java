/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.generator;

import java.io.StringWriter;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.fineract.infrastructure.report.migration.model.PentahoReportModel;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;

@Service
public class ReportModelMapper {

    private final BirtDomBuilder domBuilder;

    public ReportModelMapper(BirtDomBuilder domBuilder) {
        this.domBuilder = domBuilder;
    }

    /**
     * Maps the Pentaho IR into a raw BIRT .rptdesign XML String.
     */
    public String mapToBirtTemplate(PentahoReportModel pentahoModel) {
        try {
            Document birtDocument = domBuilder.buildReportDocument(pentahoModel);
            return transformDocumentToString(birtDocument);
        } catch (ParserConfigurationException | TransformerException e) {
            throw new RuntimeException("Failed to generate BIRT XML DOM for report: " + pentahoModel.reportName(), e);
        }
    }

    private String transformDocumentToString(Document document) throws TransformerException {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        
        // Ensure standard BIRT formatting
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }
}