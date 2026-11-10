/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Optional savings-plugin data, read through Fineract's tenant-routing JdbcTemplate. */
@Repository
@RequiredArgsConstructor
public class KycReportRepository {
    private static final Set<String> VALIDATION_TABLES = Set.of("dt_kyc_validation", "dt_kyc_validation_entity");
    private final JdbcTemplate jdbc;

    public Map<String, Object> latestVerification(long clientId) {
        if (!tableExists("m_client_kyc_verification")) return Map.of();
        var rows = jdbc.queryForList(
                "SELECT kyc_status, created_on_utc FROM m_client_kyc_verification "
                        + "WHERE client_id = ? ORDER BY created_on_utc DESC, id DESC LIMIT 1",
                clientId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    public Map<String, Object> validation(String table, long clientId) {
        if (!VALIDATION_TABLES.contains(table)) throw new IllegalArgumentException("Unknown KYC datatable");
        Integer registered = jdbc.queryForObject(
                "SELECT COUNT(*) FROM x_registered_table "
                        + "WHERE registered_table_name = ? AND application_table_name = 'm_client'",
                Integer.class,
                table);
        if (registered == null || registered == 0 || !tableExists(table)) return Map.of();
        // Only the fixed, allowlisted table names above enter SQL. Client IDs are always bound.
        // The Web App stores these as single-row client datatables. Read only that client's row.
        var rows = jdbc.queryForList("SELECT * FROM " + table + " WHERE client_id = ? LIMIT 1", clientId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private boolean tableExists(String table) {
        // Check before SELECT: catching a missing relation after executing it would leave a
        // PostgreSQL transaction aborted. Do not swallow SQL, schema or database permission errors.
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            var metadata = connection.getMetaData();
            String escape = metadata.getSearchStringEscape();
            String pattern = table.replace("_", escape + "_");
            try (var tables = metadata.getTables(
                    connection.getCatalog(), connection.getSchema(), pattern, new String[] {"TABLE"})) {
                while (tables.next()) {
                    if (table.equals(tables.getString("TABLE_NAME"))) return true;
                }
                return false;
            }
        }));
    }
}
