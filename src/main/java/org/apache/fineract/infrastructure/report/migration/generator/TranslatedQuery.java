/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.generator;

import java.util.List;

/**
 * Holds the translated BIRT-compatible SQL query and the ordered list of parameter names required
 * for positional binding.
 */
public record TranslatedQuery(String birtSqlQuery, List<String> orderedParameterNames) {}
