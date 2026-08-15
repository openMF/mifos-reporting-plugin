/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.util;

/** Utility class for file-related operations, especially filename sanitization. */
public final class FilenameUtils {

    private FilenameUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Sanitizes a filename by removing or replacing illegal characters.
     *
     * @param name the original filename
     * @return a safe filename
     */
    public static String sanitizeFilename(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "report";
        }

        // Replace all characters that are not alphanumeric, underscore, dot, or hyphen
        String sanitized = name.trim().replaceAll("[^a-zA-Z0-9_.-]", "_");

        // Prevent names that are just dots or too short
        if (sanitized.isEmpty() || sanitized.equals(".")) {
            return "report";
        }

        return sanitized;
    }

    /** Sanitizes filename and appends extension if not present. */
    public static String sanitizeFilenameWithExtension(String name, String extension) {
        String baseName = sanitizeFilename(name);

        if (extension == null || extension.trim().isEmpty()) {
            return baseName;
        }

        // Ensure extension starts with dot
        String ext = extension.trim();
        if (!ext.startsWith(".")) {
            ext = "." + ext;
        }

        // Avoid double extension
        if (baseName.toLowerCase().endsWith(ext.toLowerCase())) {
            return baseName;
        }

        return baseName + ext;
    }
}
