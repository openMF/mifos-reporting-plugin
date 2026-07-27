/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("BirtDataTypeMapper Tests")
class BirtDataTypeMapperTest {

  @ParameterizedTest
  @CsvSource({
    "java.lang.String, string",
    "java.lang.Integer, integer",
    "java.lang.Short, integer",
    "java.lang.Long, integer",
    "java.math.BigInteger, integer",
    "java.lang.Number, decimal",
    "java.lang.Double, decimal",
    "java.lang.Float, decimal",
    "java.math.BigDecimal, decimal",
    "java.util.Date, date",
    "java.sql.Date, date",
    "java.sql.Timestamp, datetime",
    "java.sql.Time, datetime",
    "java.lang.Boolean, boolean"
  })
  @DisplayName("Should correctly map standard Pentaho types to BIRT types")
  void shouldMapStandardTypes(String pentahoType, String expectedBirtType) {
    assertEquals(expectedBirtType, BirtDataTypeMapper.mapType(pentahoType));
  }

  @Test
  @DisplayName("Should fallback to string for unknown or custom data types")
  void shouldFallbackToStringForUnknownTypes() {
    assertEquals("string", BirtDataTypeMapper.mapType("org.apache.custom.MyType"));
    assertEquals("string", BirtDataTypeMapper.mapType("java.util.UUID"));
  }

  @Test
  @DisplayName("Should handle null, empty, and whitespace gracefully")
  void shouldHandleNullAndEmptyGracefully() {
    assertEquals("string", BirtDataTypeMapper.mapType(null));
    assertEquals("string", BirtDataTypeMapper.mapType(""));
    assertEquals("string", BirtDataTypeMapper.mapType("   "));
  }

  @Test
  @DisplayName("Should correctly map types with leading or trailing whitespace")
  void shouldMapTypesWithWhitespace() {
    assertEquals("integer", BirtDataTypeMapper.mapType(" java.lang.Integer "));
    assertEquals("date", BirtDataTypeMapper.mapType("java.util.Date\t"));
    // Verifying Unicode whitespace handling (CodeRabbit request)
    assertEquals("integer", BirtDataTypeMapper.mapType("\u2003java.lang.Integer\u2003"));
  }
}
