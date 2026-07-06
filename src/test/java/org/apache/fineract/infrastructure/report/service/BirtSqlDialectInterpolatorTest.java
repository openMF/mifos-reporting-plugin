/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Collections;
import javax.sql.DataSource;
import org.eclipse.birt.report.model.api.OdaDataSetHandle;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("BirtSqlDialectInterpolator Tests")
class BirtSqlDialectInterpolatorTest {

  @Mock private DataSource dataSource;
  @Mock private Connection connection;
  @Mock private DatabaseMetaData metaData;
  @Mock private ReportDesignHandle designHandle;
  @Mock private OdaDataSetHandle dataSetHandle; // Updated to match the specific ODA class

  @InjectMocks private BirtSqlDialectInterpolator interpolator;

  @BeforeEach
  void setUp() throws Exception {
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metaData);

    // Mock the BIRT design handle to return our mocked ODA dataset
    when(designHandle.getAllDataSets()).thenReturn(Collections.singletonList(dataSetHandle));
  }

  @Test
  @DisplayName("Should translate MySQL functions to PostgreSQL syntax")
  void shouldTranslateForPostgres() throws Exception {
    // Simulate Fineract running on PostgreSQL
    when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");

    // The legacy query in the BIRT template
    String legacyQuery =
        "SELECT * FROM `m_client` WHERE FUNC_NULL(display_name, '') = 'Test' AND ifnull(status, 1) = 1";
    when(dataSetHandle.getQueryText()).thenReturn(legacyQuery);

    interpolator.interpolate(designHandle);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(dataSetHandle).setQueryText(sqlCaptor.capture());

    String translatedSql = sqlCaptor.getValue();

    // Asserts: Backticks removed, FUNC_NULL replaced with coalesce, ifnull replaced with coalesce
    assertEquals(
        "SELECT * FROM m_client WHERE coalesce(display_name, '') = 'Test' AND coalesce(status, 1) = 1",
        translatedSql);
  }

  @Test
  @DisplayName("Should translate abstract functions to MariaDB syntax")
  void shouldTranslateForMariaDb() throws Exception {
    // Simulate Fineract running on MariaDB
    when(metaData.getDatabaseProductName()).thenReturn("MariaDB");

    // The query in the BIRT template
    String templateQuery = "SELECT * FROM `m_client` WHERE FUNC_NULL(display_name, '') = 'Test'";
    when(dataSetHandle.getQueryText()).thenReturn(templateQuery);

    interpolator.interpolate(designHandle);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(dataSetHandle).setQueryText(sqlCaptor.capture());

    String translatedSql = sqlCaptor.getValue();

    // Asserts: Backticks remain (MariaDB supports them), FUNC_NULL replaced with ifnull
    assertEquals("SELECT * FROM `m_client` WHERE ifnull(display_name, '') = 'Test'", translatedSql);
  }
}
