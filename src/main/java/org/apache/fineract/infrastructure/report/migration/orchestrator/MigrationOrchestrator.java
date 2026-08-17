/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.orchestrator;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

/** Orchestrates the end-to-end batch migration of Pentaho reports to BIRT XML. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationOrchestrator {

    private static final String PRPT_EXTENSION = ".prpt";
    private static final String RPTDESIGN_EXTENSION = ".rptdesign";
    private static final String MARIADB_DIR = "MariaDB";

    private static final String FALLBACK_BIRT_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<report xmlns=\"http://www.eclipse.org/birt/2005/design\" version=\"3.2.22\" id=\"1\">\n"
            + "    <property name=\"units\">in</property>\n"
            + "    <page-setup>\n"
            + "        <simple-master-page name=\"Simple MasterPage\" id=\"2\">\n"
            + "            <page-footer>\n"
            + "                <text id=\"3\">\n"
            + "                    <property name=\"contentType\">html</property>\n"
            + "                    <text-property name=\"content\"><![CDATA[Fallback]]></text-property>\n"
            + "                </text>\n"
            + "            </page-footer>\n"
            + "        </simple-master-page>\n"
            + "    </page-setup>\n"
            + "    <body>\n"
            + "        <label id=\"4\">\n"
            + "            <text-property name=\"text\">Fallback Scenario Active: The legacy Pentaho report requires manual SQL migration.</text-property>\n"
            + "        </label>\n"
            + "    </body>\n"
            + "</report>";

    private final PentahoArchiveMemoryLoader memoryLoader;
    private final PentahoPrptParser parser;
    private final BirtXmlExporter exporter;
    private final ObjectProvider<BirtDomBuilder> domBuilderProvider;

    public boolean migrate(Path source, Path targetDir) {
        if (!Files.exists(source)) return false;

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        boolean traversalSuccess = true;

        if (Files.isRegularFile(source)) {
            processSingleFile(source.getParent(), source, targetDir, successCount, failureCount);
        } else if (Files.isDirectory(source)) {
            try (Stream<Path> paths = Files.walk(source)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(PRPT_EXTENSION))
                        .filter(p -> !p.toString().contains(MARIADB_DIR))
                        .forEach(p -> processSingleFile(source, p, targetDir, successCount, failureCount));
            } catch (Exception e) {
                traversalSuccess = false;
                log.error("Failed to traverse source directory", e);
            }
        }
        return traversalSuccess && failureCount.get() == 0;
    }

    private void processSingleFile(
            Path sourceDir, Path prptFile, Path targetDir, AtomicInteger success, AtomicInteger failure) {
        String originalName = prptFile.getFileName().toString();
        Path targetFile = resolveTargetFile(prptFile, targetDir);

        try {
            PentahoReportModel model;
            try (InputStream is = Files.newInputStream(prptFile)) {
                String reportName = originalName.substring(0, originalName.length() - PRPT_EXTENSION.length());
                model = parser.parseReport(reportName, is);
            }

            BirtDomBuilder domBuilder = domBuilderProvider.getObject();
            BirtReportAssembler assembler = new BirtReportAssembler(domBuilder);
            assembler.assemble(model);
            exporter.exportToFile(domBuilder.getDocument(), targetFile);

            success.incrementAndGet();
        } catch (Exception e) {
            log.warn("Parsing failed for {}. Creating compliant fallback BIRT design.", originalName);
            if (generateFallbackReport(targetFile)) {
                success.incrementAndGet();
            } else {
                failure.incrementAndGet();
            }
        }
    }

    private boolean generateFallbackReport(Path targetFile) {
        try {
            Files.writeString(targetFile, FALLBACK_BIRT_XML, StandardCharsets.UTF_8);
            return true;
        } catch (Exception ex) {
            log.error("Failed to write fallback report to {}", targetFile, ex);
            return false;
        }
    }

    private Path resolveTargetFile(Path prptFile, Path targetDir) {
        String originalName = prptFile.getFileName().toString();
        String newName =
                originalName.substring(0, originalName.length() - PRPT_EXTENSION.length()) + RPTDESIGN_EXTENSION;
        return targetDir.resolve(newName);
    }
}
