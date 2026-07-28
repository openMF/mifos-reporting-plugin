/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utility to translate legacy Pentaho named SQL parameters into BIRT positional parameters. */
public final class PentahoSqlTranslator {

  // Lexical tokenizer regex to safely skip SQL comments and string literals
  private static final Pattern SQL_TOKEN_PATTERN =
      Pattern.compile(
          "(/\\*[\\s\\S]*?\\*/)" // Group 1: Block comments
              + "|(--[^\\n\\r]*)" // Group 2: Line comments
              + "|('([^']|'')*')" // Group 3: Single-quoted string literals (Group 4 is inner)
              + "|(\"([^\"]|\"\")*\")" // Group 5: Double-quoted identifiers (Group 6 is inner)
              + "|(\\$\\{([^}]+)\\})" // Group 7: Pentaho parameter (Group 8 is inner name)
          );

  private PentahoSqlTranslator() {}

  /**
   * Parses a Pentaho SQL query, extracts named parameters in sequence, and replaces them with BIRT
   * positional parameters (?). Safely skips placeholders inside strings or comments.
   *
   * @param pentahoSql the original Pentaho SQL string
   * @return a {@link TranslatedQuery} containing the positional SQL and ordered parameter names
   */
  public static TranslatedQuery translate(String pentahoSql) {
    if (pentahoSql == null || pentahoSql.isBlank()) {
      return new TranslatedQuery("", List.of());
    }

    List<String> parameterNames = new ArrayList<>();
    Matcher matcher = SQL_TOKEN_PATTERN.matcher(pentahoSql);
    StringBuilder birtSql = new StringBuilder();

    while (matcher.find()) {
      if (matcher.group(1) != null
          || matcher.group(2) != null
          || matcher.group(3) != null
          || matcher.group(5) != null) {
        // It is a comment or string literal. Preserve it exactly as is safely.
        matcher.appendReplacement(birtSql, Matcher.quoteReplacement(matcher.group(0)));
      } else if (matcher.group(7) != null) {
        // It is a Pentaho parameter. Validate it is not empty.
        String paramName = matcher.group(8).strip();
        if (paramName.isEmpty()) {
          // Reject empty parameter names (e.g., ${   }), leave unchanged
          matcher.appendReplacement(birtSql, Matcher.quoteReplacement(matcher.group(0)));
        } else {
          // Valid parameter, replace with positional '?' and record the name
          parameterNames.add(paramName);
          matcher.appendReplacement(birtSql, "?");
        }
      }
    }
    matcher.appendTail(birtSql);

    return new TranslatedQuery(birtSql.toString(), parameterNames);
  }
}
