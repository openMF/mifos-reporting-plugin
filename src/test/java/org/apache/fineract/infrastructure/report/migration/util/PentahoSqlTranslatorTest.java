/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PentahoSqlTranslator Tests")
class PentahoSqlTranslatorTest {

    @Test
    @DisplayName("Should translate a single parameter correctly")
    void shouldTranslateSingleParameter() {
        String originalSql = "SELECT * FROM m_client WHERE office_id = ${officeId}";
        TranslatedQuery result = PentahoSqlTranslator.translate(originalSql);

        assertEquals("SELECT * FROM m_client WHERE office_id = ?", result.sql());
        assertEquals(List.of("officeId"), result.parameterNames());
    }

    @Test
    @DisplayName("Should translate multiple distinct parameters in order")
    void shouldTranslateMultipleParameters() {
        String originalSql = "SELECT * FROM m_loan WHERE client_id = ${clientId} AND status = ${statusId}";
        TranslatedQuery result = PentahoSqlTranslator.translate(originalSql);

        assertEquals("SELECT * FROM m_loan WHERE client_id = ? AND status = ?", result.sql());
        assertEquals(List.of("clientId", "statusId"), result.parameterNames());
    }

    @Test
    @DisplayName("Should handle repeated parameters correctly")
    void shouldHandleRepeatedParameters() {
        String originalSql = "SELECT * FROM m_savings WHERE (id = ${id} OR parent_id = ${id})";
        TranslatedQuery result = PentahoSqlTranslator.translate(originalSql);

        assertEquals("SELECT * FROM m_savings WHERE (id = ? OR parent_id = ?)", result.sql());
        assertEquals(List.of("id", "id"), result.parameterNames());
    }

    @Test
    @DisplayName("Should trim whitespace inside parameter declarations")
    void shouldTrimWhitespaceInsideParameters() {
        String originalSql = "SELECT * FROM m_client WHERE id = ${  clientId \u2003 }";
        TranslatedQuery result = PentahoSqlTranslator.translate(originalSql);

        assertEquals("SELECT * FROM m_client WHERE id = ?", result.sql());
        assertEquals(List.of("clientId"), result.parameterNames());
    }

    @Test
    @DisplayName("Should return original SQL if no parameters exist")
    void shouldReturnOriginalSqlIfNoParameters() {
        String originalSql = "SELECT count(*) FROM m_client";
        TranslatedQuery result = PentahoSqlTranslator.translate(originalSql);

        assertEquals(originalSql, result.sql());
        assertTrue(result.parameterNames().isEmpty());
    }

    @Test
    @DisplayName("Should handle null and blank inputs gracefully")
    void shouldHandleNullAndBlankInputs() {
        TranslatedQuery nullResult = PentahoSqlTranslator.translate(null);
        assertEquals("", nullResult.sql());
        assertTrue(nullResult.parameterNames().isEmpty());

        TranslatedQuery blankResult = PentahoSqlTranslator.translate("   ");
        assertEquals("", blankResult.sql());
        assertTrue(blankResult.parameterNames().isEmpty());
    }

    @Test
    @DisplayName("Should enforce null safety on direct TranslatedQuery record instantiation")
    void shouldEnforceRecordNullSafety() {
        TranslatedQuery query = new TranslatedQuery(null, null);
        assertNotNull(query.sql());
        assertEquals("", query.sql());
        assertNotNull(query.parameterNames());
        assertTrue(query.parameterNames().isEmpty());
    }

    @Test
    @DisplayName("Should handle multi-line SQL queries seamlessly")
    void shouldHandleMultiLineQueries() {
        String originalSql = """
        SELECT *
        FROM m_client c
        WHERE c.office_id = ${officeId}
          AND c.status_enum = ${status}
        """;

        String expectedSql = """
        SELECT *
        FROM m_client c
        WHERE c.office_id = ?
          AND c.status_enum = ?
        """;

        TranslatedQuery result = PentahoSqlTranslator.translate(originalSql);

        assertEquals(expectedSql, result.sql());
        assertEquals(List.of("officeId", "status"), result.parameterNames());
    }

    @Test
    @DisplayName("Should ignore placeholders inside SQL string literals and identifiers")
    void shouldIgnoreParametersInStringLiterals() {
        String sql = "SELECT '${label}' AS \"${ignored}\", '${   }' FROM m_client WHERE id = ${id}";
        TranslatedQuery result = PentahoSqlTranslator.translate(sql);

        assertEquals("SELECT '${label}' AS \"${ignored}\", '${   }' FROM m_client WHERE id = ?", result.sql());
        assertEquals(List.of("id"), result.parameterNames());
    }

    @Test
    @DisplayName("Should ignore placeholders inside SQL comments")
    void shouldIgnoreParametersInComments() {
        String sql = """
        -- this is a comment ${ignored}
        SELECT * /* block comment ${ignored2} */ FROM m_client WHERE id = ${id}
        """;
        String expected = """
        -- this is a comment ${ignored}
        SELECT * /* block comment ${ignored2} */ FROM m_client WHERE id = ?
        """;
        TranslatedQuery result = PentahoSqlTranslator.translate(sql);

        assertEquals(expected, result.sql());
        assertEquals(List.of("id"), result.parameterNames());
    }

    @Test
    @DisplayName("Should reject and preserve empty or whitespace-only placeholders")
    void shouldRejectEmptyParameters() {
        String sql = "SELECT * FROM m_client WHERE id = ${id} AND status = ${   }";
        TranslatedQuery result = PentahoSqlTranslator.translate(sql);

        assertEquals("SELECT * FROM m_client WHERE id = ? AND status = ${   }", result.sql());
        assertEquals(List.of("id"), result.parameterNames());
    }
}
