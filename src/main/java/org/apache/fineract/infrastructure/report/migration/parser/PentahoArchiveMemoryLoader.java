/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.parser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

/** MX-302: Extracts PRPT archives into a high-speed memory map. */
@Component
public class PentahoArchiveMemoryLoader {

  // CodeRabbit: Made package-private so tests can dynamically reference them
  static final int MAX_ENTRY_SIZE = 10 * 1024 * 1024; // 10 MiB limit per XML entry
  static final int MAX_TOTAL_SIZE = 50 * 1024 * 1024; // 50 MiB limit for the entire archive
  static final int MAX_ENTRIES = 1000; // Max 1000 file

  /**
   * Extracts all XML entries from a PRPT (ZIP) archive into an in-memory map, keyed by normalized
   * entry path, enforcing per-entry, aggregate, and entry-count limits to mitigate zip-bomb risk.
   *
   * @param prptStream the archive stream; consumed and closed by this method
   * @return a map of normalized XML entry path to its UTF-8 decoded content
   * @throws IOException if reading the archive fails
   * @throws PentahoMigrationException if archive limits are exceeded
   */
  public Map<String, String> loadArchive(InputStream prptStream) throws IOException {
    Map<String, String> archiveContents = new HashMap<>();
    int[] totalArchiveSize = new int[1]; // Array used to pass by reference for thread-safety
    int entryCount = 0;

    try (ZipInputStream zis = new ZipInputStream(prptStream)) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {

        entryCount++;
        if (entryCount > MAX_ENTRIES) {
          throw new PentahoMigrationException(
              "PRPT archive exceeds maximum allowed entry count of " + MAX_ENTRIES);
        }

        if (!entry.isDirectory() && entry.getName().endsWith(".xml")) {
          // CodeRabbit Fix: Normalize paths to prevent directory traversal / bypasses
          String normalizedPath =
              Paths.get(entry.getName()).normalize().toString().replace("\\", "/");
          if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
          }

          archiveContents.put(normalizedPath, readEntryContent(zis, totalArchiveSize));
        }
        zis.closeEntry();
      }
    }
    return archiveContents;
  }

  private String readEntryContent(ZipInputStream zis, int[] totalArchiveSize) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] data = new byte[2048];
    int entryBytes = 0;
    int count;

    while ((count = zis.read(data, 0, data.length)) != -1) {
      entryBytes += count;
      totalArchiveSize[0] += count;

      if (entryBytes > MAX_ENTRY_SIZE) {
        throw new PentahoMigrationException("PRPT XML entry exceeds maximum allowed size of 10MB.");
      }
      if (totalArchiveSize[0] > MAX_TOTAL_SIZE) {
        throw new PentahoMigrationException(
            "PRPT archive exceeds total aggregate size of 50MB to prevent multi-file Zip Bomb attacks.");
      }

      buffer.write(data, 0, count);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }
}
