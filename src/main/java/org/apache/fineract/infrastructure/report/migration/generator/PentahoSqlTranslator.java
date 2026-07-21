/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PentahoSqlTranslator {

  // Matches Pentaho named parameters: ${paramName}
  private static final Pattern PENTAHO_PARAM_PATTERN = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)\\}");

  /**
   * Translates a legacy Pentaho SQL query into a BIRT-compatible positional query.
   *
   * @param legacySql The raw SQL from Pentaho.
   * @return A TranslatedQuery containing the new SQL and ordered parameters.
   */
  public TranslatedQuery translateToPositional(String legacySql) {
    if (legacySql == null || legacySql.isBlank()) {
      return new TranslatedQuery("", List.of());
    }

    Matcher matcher = PENTAHO_PARAM_PATTERN.matcher(legacySql);
    List<String> orderedParams = new ArrayList<>();
    StringBuilder birtSql = new StringBuilder();

    while (matcher.find()) {
      // Group 1 extracts just the parameter name without the ${}
      orderedParams.add(matcher.group(1));
      // Replace the named parameter with BIRT's positional '?'
      matcher.appendReplacement(birtSql, "?");
    }
    matcher.appendTail(birtSql);

    // MX-311: Dialect-Aware replacements can be chained here if needed in the future
    // e.g., birtSql = replaceMariaDbFunctionsWithPostgres(birtSql.toString());

    return new TranslatedQuery(birtSql.toString(), orderedParams);
  }
}
