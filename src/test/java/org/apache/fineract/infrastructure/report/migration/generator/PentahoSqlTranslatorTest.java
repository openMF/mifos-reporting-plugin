/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.generator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PentahoSqlTranslatorTest {

  private PentahoSqlTranslator translator;

  @BeforeEach
  void setUp() {
    translator = new PentahoSqlTranslator();
  }

  @Test
  void givenQueryWithNamedParameters_whenTranslated_thenReplacesWithPositionalAndExtractsNames() {
    String legacySql =
        "SELECT * FROM m_loan l WHERE l.branch_id = ${branchId} AND l.status = ${loanStatus}";

    TranslatedQuery result = translator.translateToPositional(legacySql);

    assertThat(result.birtSqlQuery())
        .isEqualTo("SELECT * FROM m_loan l WHERE l.branch_id = ? AND l.status = ?");
    assertThat(result.orderedParameterNames()).containsExactly("branchId", "loanStatus");
  }

  @Test
  void givenQueryWithNoParameters_whenTranslated_thenReturnsOriginalQuery() {
    String legacySql = "SELECT * FROM m_office";

    TranslatedQuery result = translator.translateToPositional(legacySql);

    assertThat(result.birtSqlQuery()).isEqualTo(legacySql);
    assertThat(result.orderedParameterNames()).isEmpty();
  }

  @Test
  void givenQueryWithRepeatedParameters_whenTranslated_thenExtractsAllInOrder() {
    String legacySql = "SELECT * FROM m_loan WHERE start_date > ${date} AND end_date < ${date}";

    TranslatedQuery result = translator.translateToPositional(legacySql);

    assertThat(result.birtSqlQuery())
        .isEqualTo("SELECT * FROM m_loan WHERE start_date > ? AND end_date < ?");
    assertThat(result.orderedParameterNames()).containsExactly("date", "date");
  }

  @Test
  void givenNullOrBlankQuery_whenTranslated_thenReturnsEmptyResult() {
    TranslatedQuery nullResult = translator.translateToPositional(null);
    TranslatedQuery blankResult = translator.translateToPositional("   ");

    assertThat(nullResult.birtSqlQuery()).isEmpty();
    assertThat(nullResult.orderedParameterNames()).isEmpty();

    assertThat(blankResult.birtSqlQuery()).isEmpty();
    assertThat(blankResult.orderedParameterNames()).isEmpty();
  }
}
