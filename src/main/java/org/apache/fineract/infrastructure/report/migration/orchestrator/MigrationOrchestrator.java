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
   * Scans the source path (directory or file) for .prpt files and migrates them to the target
   * directory.
   *
   * @param source The root directory or single file containing legacy .prpt archive(s)
   * @param targetDir The output directory for the converted .rptdesign files
   * @return true if the traversal succeeded and at least one report migrated successfully (or if
   *     empty)
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
        paths
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".prpt"))
            .forEach(p -> processSingleFile(source, p, targetDir, successCount, failureCount));
      } catch (Exception e) {
        log.error("Failed to traverse source directory: {}", source, e);
        traversalSuccess = false;
      }
    }

    log.info(
        "Migration Complete! Success: {}, Failures: {}", successCount.get(), failureCount.get());
    return traversalSuccess && failureCount.get() == 0;
  }

  private void processSingleFile(
      Path sourceDir, Path prptFile, Path targetDir, AtomicInteger success, AtomicInteger failure) {
    try {
      String originalName = prptFile.getFileName().toString();
      log.info("Migrating: {}", originalName);

      Path targetFile = resolveTargetFile(sourceDir, prptFile, targetDir);

      // 1. Load and Parse
      PentahoReportModel model;
      try (InputStream is = Files.newInputStream(prptFile)) {
        String reportName = originalName.substring(0, originalName.length() - ".prpt".length());
        model = parser.parseReport(reportName, is);
      }

      // 2. Assemble DOM (Fetching a prototype bean instance to ensure thread/state safety)
      BirtDomBuilder domBuilder = domBuilderProvider.getObject();
      BirtReportAssembler assembler = new BirtReportAssembler(domBuilder);
      assembler.assemble(model);

      // 3. Export XML
      Document document = domBuilder.getDocument();
      exporter.exportToFile(document, targetFile);

      success.incrementAndGet();
    } catch (Exception e) {
      log.error("Failed to migrate report: {}", prptFile.getFileName(), e);
      failure.incrementAndGet();
    }
  }

  private Path resolveTargetFile(Path sourceDir, Path prptFile, Path targetDir) {
    Path relativePath =
        (sourceDir != null) ? sourceDir.relativize(prptFile) : prptFile.getFileName();
    String originalName = relativePath.getFileName().toString();
    String baseName = originalName.substring(0, originalName.length() - ".prpt".length());
    String newName = baseName + ".rptdesign";
    return targetDir.resolve(relativePath).resolveSibling(newName);
  }
}
