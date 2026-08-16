/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.orchestrator;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.report.migration.builder.BirtDomBuilder;
import org.apache.fineract.infrastructure.report.migration.builder.BirtReportAssembler;
import org.apache.fineract.infrastructure.report.migration.exporter.BirtXmlExporter;
import org.apache.fineract.infrastructure.report.migration.model.PentahoReportModel;
import org.apache.fineract.infrastructure.report.migration.parser.PentahoArchiveMemoryLoader;
import org.apache.fineract.infrastructure.report.migration.parser.PentahoPrptParser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Orchestrates the end-to-end batch migration of Pentaho reports to BIRT XML. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationOrchestrator {

    private final PentahoArchiveMemoryLoader memoryLoader;
    private final PentahoPrptParser parser;
    private final BirtXmlExporter exporter;
    private final ObjectProvider<BirtDomBuilder> domBuilderProvider;

    /**
     * Executes the migration pipeline for a single file or a directory of files.
     *
     * @param source The source .prpt file or directory
     * @param targetDir The output directory for the converted .rptdesign files
     * @return true if the traversal and fallback generation succeeded without hard crashes
     */
    public boolean migrate(Path source, Path targetDir) {
        log.info("Starting migration from {} to {}", source, targetDir);

        if (!Files.exists(source)) {
            log.error("Source path does not exist: {}", source);
            return false;
        }

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        boolean traversalSuccess = true;

        if (Files.isRegularFile(source)) {
            if (!source.toString().endsWith(".prpt")) {
                log.error("Provided file is not a .prpt archive: {}", source);
                return false;
            }
            processSingleFile(source.getParent(), source, targetDir, successCount, failureCount);
        } else if (Files.isDirectory(source)) {
            try (Stream<Path> paths = Files.walk(source)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".prpt"))
                        .forEach(p -> processSingleFile(source, p, targetDir, successCount, failureCount));
            } catch (Exception e) {
                log.error("Failed to traverse source directory: {}", source, e);
                traversalSuccess = false;
            }
        }

        log.info("Migration Complete! Processed: {}, Hard Failures: {}", successCount.get(), failureCount.get());
        return traversalSuccess && failureCount.get() == 0;
    }

    private void processSingleFile(
            Path sourceDir, Path prptFile, Path targetDir, AtomicInteger success, AtomicInteger failure) {
        String originalName = prptFile.getFileName().toString();
        Path targetFile = resolveTargetFile(sourceDir, prptFile, targetDir);

        try {
            log.info("Migrating: {}", originalName);

            PentahoReportModel model;
            try (InputStream is = Files.newInputStream(prptFile)) {
                String reportName = originalName.substring(0, originalName.length() - ".prpt".length());
                model = parser.parseReport(reportName, is);
            }

            BirtDomBuilder domBuilder = domBuilderProvider.getObject();
            BirtReportAssembler assembler = new BirtReportAssembler(domBuilder);
            assembler.assemble(model);

            Document document = domBuilder.getDocument();
            exporter.exportToFile(document, targetFile);

            success.incrementAndGet();
        } catch (Exception e) {
            log.warn("Migration failed for {}. Triggering Fallback Handler.", originalName);
            if (generateFallbackReport(targetFile, originalName, e)) {
                success.incrementAndGet(); // Counted as handled fallback
            } else {
                failure.incrementAndGet();
            }
        }
    }

    private boolean generateFallbackReport(Path targetFile, String originalName, Exception e) {
        try {
            BirtDomBuilder domBuilder = domBuilderProvider.getObject();
            Element body = domBuilder.appendElement(domBuilder.getReportRoot(), "body");
            Element text = domBuilder.appendElement(body, "text");

            Element textProp = domBuilder.appendElement(text, "text-property");
            textProp.setAttribute("name", "content");
            textProp.setTextContent("Fallback Scenario Active: The legacy Pentaho report '"
                    + originalName
                    + "' contains unsupported XML structures and requires manual BIRT Designer migration.");

            exporter.exportToFile(domBuilder.getDocument(), targetFile);
            return true;
        } catch (Exception ex) {
            log.error("Fallback XML generation completely failed for {}", originalName, ex);
            return false;
        }
    }

    private Path resolveTargetFile(Path sourceDir, Path prptFile, Path targetDir) {
        Path relativePath = (sourceDir != null) ? sourceDir.relativize(prptFile) : prptFile.getFileName();
        String originalName = relativePath.toString();

        // Replace directory separators with underscores to create a collision-safe flat name
        String flatName = originalName.replace("/", "_").replace("\\", "_");
        String baseName = flatName.substring(0, flatName.length() - ".prpt".length());
        String newName = baseName + ".rptdesign";

        return targetDir.resolve(newName);
    }
}
