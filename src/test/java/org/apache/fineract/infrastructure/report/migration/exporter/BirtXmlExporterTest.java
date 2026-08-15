/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.fineract.infrastructure.report.migration.builder.BirtDomBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

@DisplayName("BirtXmlExporter Tests")
class BirtXmlExporterTest {

    private BirtXmlExporter exporter;
    private Document mockDocument;

    @BeforeEach
    void setUp() throws Exception {
        exporter = new BirtXmlExporter();

        BirtDomBuilder builder = new BirtDomBuilder();
        builder.appendProperty(builder.getReportRoot(), "testProp", "CodeRabbit");
        mockDocument = builder.getDocument();
    }

    @Test
    @DisplayName("Should export Document to a formatted XML string")
    void shouldExportAsString() {
        String xmlOutput = exporter.exportAsString(mockDocument);

        assertThat(xmlOutput).isNotBlank();

        // Safer assertions that don't depend on strict standalone attributes
        assertThat(xmlOutput).contains("<?xml");
        assertThat(xmlOutput).contains("encoding=\"UTF-8\"");
        assertThat(xmlOutput).contains("<report");
        assertThat(xmlOutput).contains("xmlns=\"http://www.eclipse.org/birt/2005/design\"");

        // Verify indentation logic was applied (normalize CRLF/LF for portability)
        String normalized = xmlOutput.replace("\r\n", "\n");
        assertThat(normalized).containsPattern("\n {4}<property name=\"testProp\">");
        assertThat(normalized).contains("CodeRabbit</property>");
    }

    @Test
    @DisplayName("Should export Document directly to filesystem")
    void shouldExportToFile(@TempDir Path tempDir) throws Exception {
        Path outputPath = tempDir.resolve("test_report.rptdesign");

        exporter.exportToFile(mockDocument, outputPath);

        assertThat(Files.exists(outputPath)).isTrue();
        String fileContent = Files.readString(outputPath);
        String normalized = fileContent.replace("\r\n", "\n");
        assertThat(normalized).containsPattern("\n {4}<property name=\"testProp\">");
    }

    @Test
    @DisplayName("Should reject null arguments gracefully")
    void shouldRejectNulls() {
        assertThatThrownBy(() -> exporter.exportAsString(null))
                .isInstanceOf(BirtXmlExportException.class)
                .hasMessage("Cannot export a null Document");

        // Add this missing test for the exportToFile document null check
        assertThatThrownBy(() -> exporter.exportToFile(null, Path.of("unused.rptdesign")))
                .isInstanceOf(BirtXmlExportException.class)
                .hasMessage("Cannot export a null Document");

        assertThatThrownBy(() -> exporter.exportToFile(mockDocument, null))
                .isInstanceOf(BirtXmlExportException.class)
                .hasMessage("Output path cannot be null");
    }

    @Test
    @DisplayName("Should securely replace an existing parentless path using atomic move")
    void shouldExportToParentlessPath() throws Exception {
        Path parentlessPath = Path.of("parentless_test_report.rptdesign");
        try {
            // Pre-create the file to test the REPLACE_EXISTING and ATOMIC_MOVE logic
            Files.writeString(parentlessPath, "OLD_DATA_THAT_SHOULD_BE_OVERWRITTEN");

            exporter.exportToFile(mockDocument, parentlessPath);

            assertThat(Files.exists(parentlessPath)).isTrue();
            String fileContent = Files.readString(parentlessPath);
            String normalized = fileContent.replace("\r\n", "\n");

            assertThat(normalized).containsPattern("\n {4}<property name=\"testProp\">");
            assertThat(fileContent).doesNotContain("OLD_DATA_THAT_SHOULD_BE_OVERWRITTEN");
        } finally {
            // Clean up the file from the project root after the test
            Files.deleteIfExists(parentlessPath);
        }
    }
}
