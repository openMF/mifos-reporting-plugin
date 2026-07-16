/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PentahoArchiveMemoryLoader TDD Tests")
class PentahoArchiveMemoryLoaderTest {

  private final PentahoArchiveMemoryLoader loader = new PentahoArchiveMemoryLoader();

  private InputStream createZipArchive(Map<String, String> entries) throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(baos)) {
      for (Map.Entry<String, String> entry : entries.entrySet()) {
        zos.putNextEntry(new ZipEntry(entry.getKey()));
        // CodeRabbit Fix: Specify UTF-8 to ensure deterministic behavior
        zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
    }
    return new ByteArrayInputStream(baos.toByteArray());
  }

  @Test
  @DisplayName("Should successfully load and normalize XML entries")
  void shouldLoadAndNormalizeEntries() throws Exception {
    Map<String, String> mockFiles =
        Map.of(
            "layout.xml", "<layout/>",
            "nested/../datadefinition.xml", "<data/>", // Tests normalization
            "image.png", "binary_data" // Should be ignored
            );

    InputStream zipStream = createZipArchive(mockFiles);
    Map<String, String> archive = loader.loadArchive(zipStream);

    assertThat(archive).hasSize(2);
    assertThat(archive).containsEntry("layout.xml", "<layout/>");
    assertThat(archive).containsEntry("datadefinition.xml", "<data/>");
  }

  @Test
  @DisplayName("Should throw exception when archive contains too many entries")
  void shouldThrowWhenTooManyEntries() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(baos)) {
      // CodeRabbit Fix: Dynamically reference the limit
      for (int i = 0; i < PentahoArchiveMemoryLoader.MAX_ENTRIES + 5; i++) {
        zos.putNextEntry(new ZipEntry("file" + i + ".xml"));
        zos.write("<xml/>".getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
    }

    InputStream zipStream = new ByteArrayInputStream(baos.toByteArray());

    assertThatThrownBy(() -> loader.loadArchive(zipStream))
        .isInstanceOf(PentahoMigrationException.class)
        .hasMessageContaining("maximum allowed entry count");
  }

  @Test
  @DisplayName("Should throw exception when a single entry exceeds maximum size")
  void shouldThrowWhenEntryExceedsMaxSize() throws Exception {
    StringBuilder largeContent = new StringBuilder();
    largeContent.append("<xml>".repeat(2_500_000)); // ~12.5MB
    InputStream zipStream = createZipArchive(Map.of("big.xml", largeContent.toString()));

    assertThatThrownBy(() -> loader.loadArchive(zipStream))
        .isInstanceOf(PentahoMigrationException.class)
        .hasMessageContaining("maximum allowed size of 10MB");
  }

  @Test
  @DisplayName("Should throw exception when total archive exceeds aggregate size")
  void shouldThrowWhenTotalExceedsMaxSize() throws Exception {
    // Create multiple entries whose combined size exceeds 50MB
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(baos)) {
      for (int i = 0; i < 6; i++) {
        zos.putNextEntry(new ZipEntry("file" + i + ".xml"));
        zos.write(new byte[9 * 1024 * 1024]); // 9MB each, 54MB total
        zos.closeEntry();
      }
    }
    InputStream zipStream = new ByteArrayInputStream(baos.toByteArray());

    assertThatThrownBy(() -> loader.loadArchive(zipStream))
        .isInstanceOf(PentahoMigrationException.class)
        .hasMessageContaining("total aggregate size of 50MB");
  }
}
