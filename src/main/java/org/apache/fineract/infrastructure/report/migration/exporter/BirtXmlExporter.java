/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.exporter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

@Component
/** Serializes the in-memory BIRT DOM into formatted XML strings or physical .rptdesign files. */
public class BirtXmlExporter {

  private static final String ENCODING_UTF8 = "UTF-8";
  private static final String FEATURE_ENABLED = "yes";
  private static final String XSLT_INDENT_AMOUNT_KEY = "{http://xml.apache.org/xslt}indent-amount";
  private static final String XSLT_INDENT_AMOUNT_VALUE = "4";

  /**
   * Transforms the DOM Document into a formatted UTF-8 XML String.
   *
   * @param document the populated BIRT report DOM
   * @return the serialized XML string
   * @throws BirtXmlExportException if the transformation fails
   */
  public String exportAsString(Document document) {
    if (document == null) {
      throw new BirtXmlExportException("Cannot export a null Document");
    }

    try {
      Transformer transformer = createSecureTransformer();
      StringWriter writer = new StringWriter();
      transformer.transform(new DOMSource(document), new StreamResult(writer));
      return writer.toString();
    } catch (TransformerException e) {
      throw new BirtXmlExportException("Failed to export BIRT DOM to XML string", e);
    }
  }

  /**
   * Serializes the DOM Document directly to the specified filesystem path using streams to minimize
   * memory footprint on large reports. Uses an atomic temporary file swap to prevent data loss on
   * failure.
   *
   * @param document the populated BIRT report DOM
   * @param outputPath the destination file path
   * @throws BirtXmlExportException if file writing or transformation fails
   */
  public void exportToFile(Document document, Path outputPath) {
    if (document == null) {
      throw new BirtXmlExportException("Cannot export a null Document");
    }
    if (outputPath == null) {
      throw new BirtXmlExportException("Output path cannot be null");
    }

    Path tempFile = null;
    try {
      // Resolve absolute path to guarantee a parent directory on the same filesystem
      Path absoluteTarget = outputPath.toAbsolutePath();
      Path parentDir = absoluteTarget.getParent();

      if (parentDir != null) {
        Files.createDirectories(parentDir);
      }

      // Create temp file in the exact same directory to ensure ATOMIC_MOVE succeeds
      tempFile = Files.createTempFile(parentDir, "birt_export_", ".rptdesign.tmp");

      try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tempFile))) {
        Transformer transformer = createSecureTransformer();
        transformer.transform(new DOMSource(document), new StreamResult(os));
      }

      // Strictly enforce atomic replacement to prevent undefined file states
      Files.move(
          tempFile,
          outputPath,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    } catch (IOException | TransformerException e) {
      throw new BirtXmlExportException("Failed to write BIRT XML to file: " + outputPath, e);
    } finally {
      if (tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
          // Ignore cleanup failures
        }
      }
    }
  }

  private Transformer createSecureTransformer() throws TransformerConfigurationException {
    TransformerFactory factory = TransformerFactory.newInstance();

    // Fail-closed: Must enforce secure processing
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

    // Fail-closed: Must explicitly disable external entities for secure XML serialization
    applyConfiguration(
        () -> {
          factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
          factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        },
        "JAXP implementation does not support external entity restriction");

    Transformer transformer = factory.newTransformer();
    transformer.setOutputProperty(OutputKeys.ENCODING, ENCODING_UTF8);
    transformer.setOutputProperty(OutputKeys.INDENT, FEATURE_ENABLED);

    // Fail-closed: Must enforce the 4-space Fineract formatting standard
    applyConfiguration(
        () -> {
          transformer.setOutputProperty(XSLT_INDENT_AMOUNT_KEY, XSLT_INDENT_AMOUNT_VALUE);
        },
        "JAXP implementation does not support strict 4-space indentation");

    return transformer;
  }

  /**
   * Helper method to functionally wrap configuration actions that may throw
   * IllegalArgumentExceptions if the underlying JAXP provider does not support specific security or
   * formatting extensions.
   */
  private void applyConfiguration(Runnable configAction, String errorMessage)
      throws TransformerConfigurationException {
    try {
      configAction.run();
    } catch (IllegalArgumentException e) {
      throw new TransformerConfigurationException(errorMessage, e);
    }
  }
}
