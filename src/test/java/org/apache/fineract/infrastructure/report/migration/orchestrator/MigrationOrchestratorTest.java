/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.fineract.infrastructure.report.migration.builder.BirtDomBuilder;
import org.apache.fineract.infrastructure.report.migration.exporter.BirtXmlExporter;
import org.apache.fineract.infrastructure.report.migration.parser.PentahoArchiveMemoryLoader;
import org.apache.fineract.infrastructure.report.migration.parser.PentahoPrptParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("MigrationOrchestrator Tests")
class MigrationOrchestratorTest {

    private MigrationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        PentahoArchiveMemoryLoader memoryLoader = new PentahoArchiveMemoryLoader();
        PentahoPrptParser parser = new PentahoPrptParser(memoryLoader);
        BirtXmlExporter exporter = new BirtXmlExporter();

        @SuppressWarnings("unchecked")
        ObjectProvider<BirtDomBuilder> domBuilderProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(domBuilderProvider.getObject()).thenAnswer(invocation -> new BirtDomBuilder());

        orchestrator = new MigrationOrchestrator(memoryLoader, parser, exporter, domBuilderProvider);
    }

    @Test
    @DisplayName("Should successfully migrate a nested .prpt archive and output a .rptdesign file")
    void shouldMigrateValidNestedReport(@TempDir Path tempSource, @TempDir Path tempTarget) throws Exception {
        Path nestedDir = Files.createDirectories(tempSource.resolve("categoryA").resolve("legacy"));
        Path mockPrpt = nestedDir.resolve("mock_report.prpt");

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(mockPrpt))) {
            zos.putNextEntry(new ZipEntry("datasources/sql-ds.xml"));
            zos.write("<data><query name=\"q1\"><static-query>SELECT 1</static-query></query></data>"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("datadefinition.xml"));
            zos.write("<data/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("layout.xml"));
            zos.write("<layout/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        boolean success = orchestrator.migrate(tempSource, tempTarget);

        assertThat(success).isTrue();

        Path expectedOutput = tempTarget.resolve("mock_report.rptdesign");

        assertThat(Files.exists(expectedOutput))
                .withFailMessage("Expected output file to exist at %s", expectedOutput)
                .isTrue();

        String xmlContent = Files.readString(expectedOutput);
        assertThat(xmlContent).contains("<report");
    }

    @Test
    @DisplayName("Should gracefully handle an existing but empty source directory")
    void shouldHandleEmptyDirectories(@TempDir Path tempSource, @TempDir Path tempTarget) throws Exception {
        boolean success = orchestrator.migrate(tempSource, tempTarget);

        assertThat(success).isTrue();
        try (Stream<Path> files = Files.list(tempTarget)) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    @DisplayName("Should return false and handle non-existent source directories safely")
    void shouldHandleInvalidDirectories(@TempDir Path tempTarget) throws Exception {
        Path nonExistentSource = tempTarget.resolve("missing-source");

        boolean success = orchestrator.migrate(nonExistentSource, tempTarget);

        assertThat(success).isFalse();
        try (Stream<Path> files = Files.list(tempTarget)) {
            assertThat(files.count()).isZero();
        }
    }
}
