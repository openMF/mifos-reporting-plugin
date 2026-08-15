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
    @DisplayName("Should successfully migrate a valid nested .prpt archive to a .rptdesign file")
    void shouldMigrateValidNestedReport(@TempDir Path tempSource, @TempDir Path tempTarget) throws Exception {
        // Arrange: Create a nested directory structure and a mock PRPT zip file
        Path nestedDir = Files.createDirectories(tempSource.resolve("categoryA").resolve("legacy"));
        Path mockPrpt = nestedDir.resolve("mock_report.prpt");

        // Generate a minimal valid Pentaho XML structure inside the PRPT archive
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

        // Act
        boolean success = orchestrator.migrate(tempSource, tempTarget);

        // Assert
        assertThat(success).isTrue();

        // Verify the output exists in the correct nested target structure
        Path expectedOutput = tempTarget.resolve("categoryA").resolve("legacy").resolve("mock_report.rptdesign");
        assertThat(Files.exists(expectedOutput)).isTrue();

        // Verify basic BIRT XML export occurred
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
        // Safely resolve a path guaranteed to be missing inside the temporary target
        Path nonExistentSource = tempTarget.resolve("missing-source");

        boolean success = orchestrator.migrate(nonExistentSource, tempTarget);

        assertThat(success).isFalse();
        try (Stream<Path> files = Files.list(tempTarget)) {
            assertThat(files.count()).isZero();
        }
    }
}
