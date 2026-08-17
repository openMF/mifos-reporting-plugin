/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.util;

public final class BirtDataTypeMapper {

    private BirtDataTypeMapper() {}

    public static String mapType(String pentahoType) {
        if (pentahoType == null || pentahoType.isBlank()) {
            return "string";
        }

        String lower = pentahoType.toLowerCase();
        if (lower.contains("int") || lower.contains("long") || lower.contains("short") || lower.contains("number")) {
            return "integer";
        } else if (lower.contains("date") || lower.contains("time") || lower.contains("timestamp")) {
            return "date";
        } else if (lower.contains("float") || lower.contains("double") || lower.contains("decimal")) {
            return "decimal";
        } else if (lower.contains("bool")) {
            return "boolean";
        }

        return "string";
    }
}
