/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.orchestrator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Command-line entry point to manually trigger the Pentaho to BIRT migration batch process. */
@Slf4j
public class MigrationCli {

    /** Executes the migration pipeline. Run via IDE or terminal from the project root. */
    public static void main(String[] args) {
        Path source = null;
        Path target = null;

        // Parse CLI flags
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h":
                case "--help":
                    printHelp();
                    System.exit(0);
                    break;
                case "-s":
                case "--source":
                case "-f":
                case "--file":
                    if (i + 1 < args.length) {
                        source = Paths.get(args[++i]);
                    } else {
                        log.error("Error: Missing value for option {}", args[i]);
                        System.exit(1);
                    }
                    break;
                case "-t":
                case "--target":
                    if (i + 1 < args.length) {
                        target = Paths.get(args[++i]);
                    } else {
                        log.error("Error: Missing value for option {}", args[i]);
                        System.exit(1);
                    }
                    break;
                default:
                    log.error("Error: Unknown option {}", args[i]);
                    printHelp();
                    System.exit(1);
            }
        }

        // Default paths if not provided
        if (source == null) source = Paths.get("pentahoReports");
        if (target == null) target = Paths.get("birt", "reports");

        // Production-Ready: Ensure target directory exists before running
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            log.error("Failed to create target directory: {}", target, e);
            System.exit(1);
        }

        // Bootstrap a minimal Spring context for Dependency Injection
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext("org.apache.fineract.infrastructure.report.migration")) {

            MigrationOrchestrator orchestrator = context.getBean(MigrationOrchestrator.class);
            boolean success = orchestrator.migrate(source, target);

            if (!success) {
                log.error("Migration pipeline finished with errors. Check the logs for failed files.");
                System.exit(1);
            }
        } catch (Exception e) {
            log.error("Failed to initialize Spring Context for Migration CLI: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void printHelp() {
        System.out.println("Usage: MigrationCli [options]");
        System.out.println("Options:");
        System.out.println("  -h, --help       Show this help message");
        System.out.println("  -s, --source     Directory containing .prpt files to migrate (default: pentahoReports)");
        System.out.println("  -f, --file       Single .prpt file to migrate");
        System.out.println("  -t, --target     Output directory for .rptdesign files (default: birt/reports)");
    }
}
