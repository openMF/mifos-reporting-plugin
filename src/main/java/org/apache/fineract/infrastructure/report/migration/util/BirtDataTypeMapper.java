/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.util;

import java.util.Map;

/** Maps standard Pentaho/Java type names to lowercase BIRT type names. */
public final class BirtDataTypeMapper {

    private static final Map<String, String> TYPE_MAPPINGS = Map.ofEntries(
            Map.entry("java.lang.String", "string"),
            Map.entry("java.lang.Integer", "integer"),
            Map.entry("java.lang.Short", "integer"),
            Map.entry("java.lang.Long", "integer"),
            Map.entry("java.math.BigInteger", "integer"),
            Map.entry("java.lang.Number", "decimal"),
            Map.entry("java.lang.Double", "decimal"),
            Map.entry("java.lang.Float", "decimal"),
            Map.entry("java.math.BigDecimal", "decimal"),
            Map.entry("java.util.Date", "date"),
            Map.entry("java.sql.Date", "date"),
            Map.entry("java.sql.Timestamp", "datetime"),
            Map.entry("java.sql.Time", "datetime"),
            Map.entry("java.lang.Boolean", "boolean"));

    private BirtDataTypeMapper() {}

    /**
     * Maps a type name, defaulting null, blank, and unsupported values to {@code string}.
     *
     * @param pentahoType type name to map
     * @return corresponding BIRT type name
     */
    public static String mapType(String pentahoType) {
        if (pentahoType == null || pentahoType.isBlank()) {
            return "string";
        }
        // Using strip() instead of trim() to safely handle Unicode whitespace
        return TYPE_MAPPINGS.getOrDefault(pentahoType.strip(), "string");
    }
}
