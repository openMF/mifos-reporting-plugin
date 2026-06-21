/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.util;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Method;
import javax.sql.DataSource;

public final class DataSourceUtils {

  private DataSourceUtils() {}

  /** Extract JDBC driver class name from datasource. */
  public static String getDriverClassName(DataSource dataSource) {

    if (dataSource == null) {
      throw new IllegalArgumentException("DataSource cannot be null");
    }

    // Handle Fineract RoutingDataSource and similar wrappers
    DataSource target = unwrapRoutingDataSource(dataSource);

    if (target != null && target != dataSource) {
      return getDriverClassName(target);
    }

    // Direct Hikari datasource
    if (dataSource instanceof HikariDataSource hikari) {

      String driverClassName = hikari.getDriverClassName();

      if (driverClassName != null && !driverClassName.isBlank()) {
        return driverClassName;
      }

      String jdbcUrl = hikari.getJdbcUrl();

      if (jdbcUrl != null) {
        return deriveDriverFromJdbcUrl(jdbcUrl);
      }
    }

    // Generic datasource unwrap
    try {

      if (dataSource.isWrapperFor(HikariDataSource.class)) {

        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        String driverClassName = hikari.getDriverClassName();

        if (driverClassName != null && !driverClassName.isBlank()) {
          return driverClassName;
        }

        return deriveDriverFromJdbcUrl(hikari.getJdbcUrl());
      }

    } catch (Exception ignored) {
    }

    throw new IllegalStateException(
        "Could not determine driver class name from DataSource: "
            + dataSource.getClass().getName());
  }

  private static DataSource unwrapRoutingDataSource(DataSource dataSource) {

    try {

      Method method = dataSource.getClass().getMethod("determineTargetDataSource");

      Object target = method.invoke(dataSource);

      if (target instanceof DataSource ds) {
        return ds;
      }

    } catch (Exception ignored) {
    }

    return null;
  }

  private static String deriveDriverFromJdbcUrl(String jdbcUrl) {

    if (jdbcUrl == null || jdbcUrl.isBlank()) {
      throw new IllegalStateException("Unable to determine driver class because JDBC URL is null");
    }

    if (jdbcUrl.startsWith("jdbc:postgresql")) {
      return "org.postgresql.Driver";
    }

    if (jdbcUrl.startsWith("jdbc:mysql")) {
      return "com.mysql.cj.jdbc.Driver";
    }

    if (jdbcUrl.startsWith("jdbc:mariadb")) {
      return "org.mariadb.jdbc.Driver";
    }

    throw new IllegalStateException("Unsupported JDBC URL: " + jdbcUrl);
  }
}
