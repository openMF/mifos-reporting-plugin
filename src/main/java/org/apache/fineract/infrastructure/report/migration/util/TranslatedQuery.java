/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.util;

import java.util.List;

/**
 * An immutable representation of a translated SQL query.
 *
 * @param sql the BIRT-compliant SQL string with positional (?) parameters
 * @param parameterNames the ordered list of extracted Pentaho parameter names
 */
public record TranslatedQuery(String sql, List<String> parameterNames) {

    /** Compact constructor ensuring defensive null-safety and list immutability. */
    public TranslatedQuery {
        sql = (sql == null) ? "" : sql;
        parameterNames = (parameterNames == null) ? List.of() : List.copyOf(parameterNames);
    }
}
