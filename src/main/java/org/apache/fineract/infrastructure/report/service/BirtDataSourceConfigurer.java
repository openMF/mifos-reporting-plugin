/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.report.util.DataSourceUtils;
import org.eclipse.birt.report.model.api.LibraryHandle;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.eclipse.birt.report.model.api.SlotHandle;
import org.springframework.stereotype.Component;

/**
 * Responsible for configuring BIRT report datasources. Note: Under the MX-299 architecture, JDBC
 * connections are now provided natively by Fineract's RoutingDataSource via Spring Application
 * Context injection. Therefore, direct JDBC configuration mutation here is bypassed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BirtDataSourceConfigurer {

  public void configureAll(ReportDesignHandle designHandle) {
    if (designHandle == null) {
      log.warn("ReportDesignHandle is null. Skipping datasource configuration.");
      return;
    }

    log.debug("Configuring datasources for report design: {}", designHandle.getName());

    configure(designHandle);
    updateSubReportDataSources(designHandle);

    log.debug("All datasources configured successfully.");
  }

  private void configure(ReportDesignHandle designHandle) {
    setConnectionDetailOnDataSources(designHandle.getDataSources());
  }

  private void updateSubReportDataSources(ReportDesignHandle designHandle) {
    List<LibraryHandle> libraries = designHandle.getAllLibraries();
    if (libraries == null || libraries.isEmpty()) {
      return;
    }

    log.debug("Found {} libraries to configure", libraries.size());

    for (LibraryHandle library : libraries) {
      setConnectionDetailOnDataSources(library.getDataSources());
      updateNestedLibraries(library);
    }
  }

  private void updateNestedLibraries(LibraryHandle libraryHandle) {
    List<LibraryHandle> nestedLibraries = libraryHandle.getAllLibraries();
    if (nestedLibraries == null || nestedLibraries.isEmpty()) {
      return;
    }

    for (LibraryHandle nested : nestedLibraries) {
      setConnectionDetailOnDataSources(nested.getDataSources());
      updateNestedLibraries(nested);
    }
  }

  private void setConnectionDetailOnDataSources(SlotHandle dataSources) {
    // No-Op.
    // Apache Fineract MX-299: BIRT now receives connections via Spring DataSource injection
    // (OdaJDBCDriverPassInConnection) in BirtReportingProcessServiceImpl.
    // Hardcoded URL and credential injection is deprecated.
  }

  /** Utility method retained for BirtParameterMapper compatibility. */
  public static String toProtocol(javax.sql.DataSource dataSource) {
    return org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection
        .resolveProtocol(DataSourceUtils.getDriverClassName(dataSource));
  }
}
