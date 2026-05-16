/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabasePasswordEncryptor;
import org.apache.fineract.infrastructure.security.constants.TenantConstants;
import org.eclipse.birt.report.model.api.DesignElementHandle;
import org.eclipse.birt.report.model.api.LibraryHandle;
import org.eclipse.birt.report.model.api.OdaDataSourceHandle;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.eclipse.birt.report.model.api.SlotHandle;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Responsible for configuring BIRT report datasources with correct tenant connection details.
 * Supports main reports, sub-reports, and libraries with nested libraries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BirtDataSourceConfigurer {

  private final DataSource tenantDataSource;
  private final DatabasePasswordEncryptor databasePasswordEncryptor;
  private final FineractProperties fineractProperties;
  private final ApplicationContext applicationContext;
  private final ReportErrorHandler reportErrorHandler;
  
  // Map of database protocol to JDBC driver class name
  private static final Map<String, String> PROTOCOL_TO_DRIVER = Map.ofEntries(
      Map.entry("jdbc:postgresql", "org.postgresql.Driver"),
      Map.entry("jdbc:postgres", "org.postgresql.Driver"),
      Map.entry("jdbc:mariadb", "org.mariadb.jdbc.Driver"),
      Map.entry("jdbc:mysql", "com.mysql.cj.jdbc.Driver"),
      Map.entry("jdbc:mysql-legacy", "com.mysql.jdbc.Driver")
  );

  /** Configures all datasources in the report (main report + subreports + libraries) */
  public void configureAll(ReportDesignHandle designHandle) {
    if (designHandle == null) {
      log.warn("ReportDesignHandle is null. Skipping datasource configuration.");
      return;
    }

    log.debug("Configuring datasources for report design: {}", designHandle.getName());

    // Configure main report datasources
    configure(designHandle);

    // Configure sub-reports and libraries
    updateSubReportDataSources(designHandle);

    log.debug("All datasources configured successfully.");
  }

  /** Configures datasources directly in the main report design */
  private void configure(ReportDesignHandle designHandle) {
    setConnectionDetailOnDataSources(designHandle.getDataSources());
  }

  /** Updates datasources in all libraries and nested libraries */
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

  /** Recursively updates nested libraries */
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

  /**
   * Core method: Updates all OdaDataSourceHandle objects with tenant-specific connection details
   */
  private void setConnectionDetailOnDataSources(SlotHandle dataSources) {
    if (dataSources == null) {
      return;
    }

    final String jdbcUrl = getTenantJdbcUrl();
    final String username = getDbUsername();
    final String password = getDbPassword();
    final String driverClass = getDriverClassName();

    Iterator<DesignElementHandle> iterator = dataSources.iterator();

    while (iterator.hasNext()) {
      DesignElementHandle element = iterator.next();

      if (element instanceof OdaDataSourceHandle dataSource) {
        try {
          dataSource.setProperty("odaURL", jdbcUrl);
          dataSource.setProperty("odaUser", username);
          dataSource.setProperty("odaPassword", password);
          dataSource.setProperty("odaDriverClass", driverClass);

          log.trace("Successfully updated datasource: {}", dataSource.getName());
        } catch (Exception e) {
          log.error("Failed to update datasource: {}", dataSource.getName(), e);
        }
      }
    }
  }
  
  /**
   * Resolves the JDBC driver class name based on the tenant's database protocol.
   * 
   * @return the fully qualified driver class name
   * @throws IllegalStateException if the protocol is unsupported
   */
  private String getDriverClassName() {
    String protocol = toProtocol(tenantDataSource).toLowerCase().trim();
    String driverClass = PROTOCOL_TO_DRIVER.get(protocol);
    
    if (StringUtils.isBlank(driverClass)) {
      log.error("Unsupported database protocol: {}. Supported protocols: {}", 
          protocol, PROTOCOL_TO_DRIVER.keySet());
      throw reportErrorHandler.reportError(
          "error.msg.reporting.driver.notfound", 
          "No JDBC driver found for protocol: " + protocol);
    }
    
    log.debug("Resolved driver class for protocol '{}': {}", protocol, driverClass);
    return driverClass;
  }

  /** Builds the JDBC URL for the current tenant (supports ReadOnly mode) */
  private String getTenantJdbcUrl() {
    FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
    FineractPlatformTenantConnection conn = tenant.getConnection();

    String protocol = toProtocol(tenantDataSource);

    String server = conn.getSchemaServer();
    String port = conn.getSchemaServerPort();
    String schema = conn.getSchemaName();
    String params = conn.getSchemaConnectionParameters();

    // Override with ReadOnly settings if enabled
    if (fineractProperties.getMode().isReadOnlyMode()) {
      server =
          getPropertyValue(
              conn.getReadOnlySchemaServer(),
              TenantConstants.PROPERTY_RO_SCHEMA_SERVER_NAME,
              server);
      port =
          getPropertyValue(
              conn.getReadOnlySchemaServerPort(),
              TenantConstants.PROPERTY_RO_SCHEMA_SERVER_PORT,
              port);
      schema =
          getPropertyValue(
              conn.getReadOnlySchemaName(), TenantConstants.PROPERTY_RO_SCHEMA_SCHEMA_NAME, schema);
      params =
          getPropertyValue(
              conn.getReadOnlySchemaConnectionParameters(),
              TenantConstants.PROPERTY_RO_SCHEMA_CONNECTION_PARAMETERS,
              params);
    }

    String jdbcUrl = toJdbcUrl(protocol, server, port, schema, params);
    log.debug("Tenant JDBC URL resolved: {}", jdbcUrl);

    return jdbcUrl;
  }

  private String getDbUsername() {
    FineractPlatformTenantConnection conn = ThreadLocalContextUtil.getTenant().getConnection();

    if (StringUtils.isBlank(conn.getSchemaUsername())) {
      log.error("No username found in DB for tenant");
      throw reportErrorHandler.reportError(
          "error.msg.reporting.username.notfound", "No username found in DB for tenant");
    }
    return conn.getSchemaUsername().trim();
  }

  private String getDbPassword() {
    FineractPlatformTenantConnection conn = ThreadLocalContextUtil.getTenant().getConnection();

    if (StringUtils.isBlank(conn.getSchemaPassword())) {
      log.error("No password found in DB for tenant");
      throw reportErrorHandler.reportError(
          "error.msg.reporting.password.notfound", "No password found in DB for tenant");
    }

    try {
      return databasePasswordEncryptor.decrypt(conn.getSchemaPassword()).trim();
    } catch (Exception e) {
      log.error("Failed to decrypt database password", e);
      throw new RuntimeException("Database password decryption failed", e);
    }
  }
  
  private String getPropertyValue(String baseValue, String propertyName, String defaultValue) {
    if (StringUtils.isNotBlank(baseValue)) {
      return baseValue;
    }
    return applicationContext.getEnvironment().getProperty(propertyName, defaultValue);
  }

  protected static String toProtocol(javax.sql.DataSource dataSource) {
    return org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection
        .toProtocol(dataSource);
  }

  private static String toJdbcUrl(
      String protocol, String server, String port, String schema, String params) {
    return org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection
        .toJdbcUrl(protocol, server, port, schema, params);
  }
}
