/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

class KycReportRepositoryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    private final ResultSet tables = mock(ResultSet.class);
    private final KycReportRepository repository = new KycReportRepository(jdbc);

    @BeforeEach
    void setup() throws Exception {
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("tenant_db");
        when(connection.getSchema()).thenReturn("public");
        when(metadata.getSearchStringEscape()).thenReturn("\\");
        when(metadata.getTables(eq("tenant_db"), eq("public"), anyString(), any()))
                .thenReturn(tables);
        when(jdbc.execute(any(ConnectionCallback.class)))
                .thenAnswer(i -> ((ConnectionCallback<?>) i.getArgument(0)).doInConnection(connection));
    }

    @Test
    void absentOptionalTableDoesNotIssueFailingSql() throws Exception {
        assertEquals(Map.of(), repository.latestVerification(7));
        verify(jdbc, never()).queryForList(anyString(), anyLong());
        verify(tables).close();
    }

    @Test
    void latestVerificationIsBoundLimitedAndDeterministic() throws Exception {
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_NAME")).thenReturn("m_client_kyc_verification");
        var record = Map.<String, Object>of("kyc_status", "PENDING");
        String sql =
                "SELECT kyc_status, created_on_utc FROM m_client_kyc_verification WHERE client_id = ? ORDER BY created_on_utc DESC, id DESC LIMIT 1";
        when(jdbc.queryForList(sql, 7L)).thenReturn(List.of(record));
        assertEquals(record, repository.latestVerification(7));
        verify(jdbc).queryForList(sql, 7L);
    }

    @Test
    void validationMustBeRegisteredAgainstClientsAndPhysicallyExist() {
        assertEquals(Map.of(), repository.validation("dt_kyc_validation", 7));
        verify(jdbc, never()).execute(any(ConnectionCallback.class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("dt_kyc_validation")))
                .thenReturn(1);
        assertEquals(Map.of(), repository.validation("dt_kyc_validation", 7));
        verify(jdbc, never()).queryForList(anyString(), anyLong());
    }

    @Test
    void entityDatatableIsAllowlistedAndClientIdIsBound() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("dt_kyc_validation_entity")))
                .thenReturn(1);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_NAME")).thenReturn("dt_kyc_validation_entity");
        var record = Map.<String, Object>of("validation_status", "COMPLETE");
        when(jdbc.queryForList("SELECT * FROM dt_kyc_validation_entity WHERE client_id = ? LIMIT 1", 7L))
                .thenReturn(List.of(record));
        assertEquals(record, repository.validation("dt_kyc_validation_entity", 7));
        assertThrows(IllegalArgumentException.class, () -> repository.validation("m_client; DROP TABLE m_client", 7));
    }

    @Test
    void databaseFailuresAreNotMisrepresentedAsMissingInformation() {
        when(jdbc.execute(any(ConnectionCallback.class)))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThrows(DataAccessResourceFailureException.class, () -> repository.latestVerification(7));
    }
}
