/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.infrastructure.report.utils;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource; // fallback

public class DataSourceUtils {

    /**
     * Extracts the JDBC driver class name from a DataSource.
     * Works primarily with HikariDataSource (Fineract default).
     */
    public static String getDriverClassName(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource cannot be null");
        }

        // Primary path: HikariCP (most common in Spring Boot / Fineract)
        if (dataSource instanceof HikariDataSource hikari) {
            String driverClassName = hikari.getDriverClassName();
            if (driverClassName != null && !driverClassName.isBlank()) {
                return driverClassName;
            }
        }

        // Fallback 1: Spring's DriverManagerDataSource
        if (dataSource instanceof DriverManagerDataSource dmds) {
            return dmds.getClass().getName();
        }

        // Fallback 2: Try to unwrap (for proxies/wrappers)
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
                return hikari.getDriverClassName();
            }
            if (dataSource.isWrapperFor(DriverManagerDataSource.class)) {
                DriverManagerDataSource dmds = dataSource.unwrap(DriverManagerDataSource.class);
                return dmds.getClass().getName();
            }
        } catch (Exception e) {
            // ignore unwrap failures
        }

        // Ultimate fallback: Try to derive from JDBC URL (less reliable but helpful)
        String url = getJdbcUrl(dataSource);
        if (url != null) {
            if (url.startsWith("jdbc:postgresql")) {
                return "org.postgresql.Driver";
            } else if (url.startsWith("jdbc:mysql")) {
                return "com.mysql.cj.jdbc.Driver";
            } else if (url.startsWith("jdbc:mariadb")) {
                return "org.mariadb.jdbc.Driver";
            }
            // Add more as needed
        }

        throw new IllegalStateException("Could not determine driver class name from DataSource");
    }

    /**
     * Helper to extract JDBC URL (useful for fallback).
     */
    private static String getJdbcUrl(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getJdbcUrl();
        }
        // Add other pool-specific getters if needed
        return null;
    }
}