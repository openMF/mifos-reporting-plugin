/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.util;

import static org.assertj.core.api.Assertions.assertThat;
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

        assertEquals("SELECT * FROM m_client WHERE office_id =  ? ", result.sql());
        assertEquals(List.of("officeId"), result.parameterNames());
    }

    @Test
    @DisplayName("Should translate multiple distinct parameters in order")
    void shouldTranslateMultipleParameters() {
        String originalSql = "SELECT * FROM m_loan WHERE client_id = ${clientId} AND status = ${statusId}";
        TranslatedQuery result = PentahoSqlTranslator.translate(originalSql);

        assertEquals("SELECT * FROM m_loan WHERE client_id =  ?  AND status =  ? ", result.sql());
        assertEquals(List.of("clientId", "statusId"), result.parameterNames());
    }

    @Test
    @DisplayName("Should handle repeated parameters correctly")
    void shouldHandleRepeatedParameters() {
        String originalSql = "SELECT * FROM m_savings WHERE (id = ${id} OR parent_id = ${id})";
        TranslatedQuery result = PentahoSqlTranslator.translate(originalSql);

        assertEquals("SELECT * FROM m_savings WHERE (id =  ?  OR parent_id =  ? )", result.sql());
        assertEquals(List.of("id", "id"), result.parameterNames());
    }

    @Test
    @DisplayName("Should trim whitespace inside parameter declarations")
    void shouldTrimWhitespaceInsideParameters() {
        String originalSql = "SELECT * FROM m_client WHERE id = ${  clientId \u2003 }";
        TranslatedQuery result = PentahoSqlTranslator.translate(originalSql);

        assertEquals("SELECT * FROM m_client WHERE id =  ? ", result.sql());
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
        // FIX: Replaced text blocks with standard strings to prevent Java from deleting trailing whitespace
        String originalSql = "SELECT *\n" + "FROM m_client c\n"
                + "WHERE c.office_id = ${officeId}\n"
                + "  AND c.status_enum = ${status}\n";

        String expectedSql =
                "SELECT *\n" + "FROM m_client c\n" + "WHERE c.office_id =  ? \n" + "  AND c.status_enum =  ? \n";

        TranslatedQuery result = PentahoSqlTranslator.translate(originalSql);

        assertEquals(expectedSql, result.sql());
        assertEquals(List.of("officeId", "status"), result.parameterNames());
    }

    @Test
    @DisplayName("Should ignore placeholders inside SQL string literals and identifiers")
    void shouldIgnoreParametersInStringLiterals() {
        String sql = "SELECT '${label}' AS \"${ignored}\", '${   }' FROM m_client WHERE id = ${id}";
        TranslatedQuery result = PentahoSqlTranslator.translate(sql);

        assertEquals("SELECT '${label}' AS \"${ignored}\", '${   }' FROM m_client WHERE id =  ? ", result.sql());
        assertEquals(List.of("id"), result.parameterNames());
    }

    @Test
    @DisplayName("Should ignore placeholders inside SQL comments")
    void shouldIgnoreParametersInComments() {
        // FIX: Replaced text blocks with standard strings to prevent trailing whitespace deletion
        String sql = "-- this is a comment ${ignored}\n"
                + "SELECT * /* block comment ${ignored2} */ FROM m_client WHERE id = ${id}\n";

        String expected = "-- this is a comment ${ignored}\n"
                + "SELECT * /* block comment ${ignored2} */ FROM m_client WHERE id =  ? \n";

        TranslatedQuery result = PentahoSqlTranslator.translate(sql);

        assertEquals(expected, result.sql());
        assertEquals(List.of("id"), result.parameterNames());
    }

    @Test
    @DisplayName("Should reject and preserve empty or whitespace-only placeholders")
    void shouldRejectEmptyParameters() {
        String sql = "SELECT * FROM m_client WHERE id = ${id} AND status = ${   }";
        TranslatedQuery result = PentahoSqlTranslator.translate(sql);

        assertEquals("SELECT * FROM m_client WHERE id =  ?  AND status = ${   }", result.sql());
        assertEquals(List.of("id"), result.parameterNames());
    }

    @Test
    @DisplayName("Should correctly decode doubled backticks into single backticks inside PostgreSQL double quotes")
    void shouldDecodeDoubledBackticks() {
        String pentahoSql = "SELECT `user``name` FROM `table``_name`";
        TranslatedQuery query = PentahoSqlTranslator.translate(pentahoSql);

        assertThat(query.sql()).isEqualTo("SELECT \"user`name\" FROM \"table`_name\"");
    }

    @Test
    @DisplayName("Should convert MySQL string literal backslash escapes to standard PostgreSQL syntax")
    void shouldConvertMySqlEscapedStrings() {
        String sql = "SELECT 'It\\'s a test', 'Line\\\\Break' FROM m_client";
        TranslatedQuery result = PentahoSqlTranslator.translate(sql);
        assertThat(result.sql()).isEqualTo("SELECT 'It''s a test', 'Line\\Break' FROM m_client");
    }

    @Test
    @DisplayName("Should correctly translate nested IF statements containing internal function commas")
    void shouldTranslateNestedIfStatements() {
        String pentahoSql = "SELECT IF(status = 1, IF(active = 1, CONCAT(first, last), 'N/A'), 'Unknown') FROM users";
        TranslatedQuery query = PentahoSqlTranslator.translate(pentahoSql);

        assertThat(query.sql())
                .isEqualTo(
                        "SELECT CASE WHEN status = 1 THEN CASE WHEN active = 1 THEN CONCAT(first, last) ELSE 'N/A' END ELSE 'Unknown' END FROM users");
    }
}
