/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import java.sql.Connection;
import java.util.Iterator;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.eclipse.birt.report.model.api.OdaDataSetHandle;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.eclipse.birt.report.model.api.activity.SemanticException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

/**
 * Architectural Component for MX-297 & MX-298. Intercepts BIRT report templates before execution
 * and interpolates SQL queries at runtime to guarantee database-agnostic cross-compatibility.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BirtSqlDialectInterpolator {

    private final DataSource dataSource;

    private static final Pattern MYSQL_IFNULL_PATTERN = Pattern.compile("(?i)\\bifnull\\s*\\(");
    private static final Pattern GOV_BACKTICK_PATTERN = Pattern.compile("`");

    /**
     * Intercepts the BIRT report design and translates SQL queries at runtime. Modifies underlying
     * dataset queries to match the active database dialect (e.g., PostgreSQL or MariaDB), ensuring
     * cross-database compatibility.
     *
     * @param designHandle The parsed BIRT report design handle.
     */
    public void interpolate(ReportDesignHandle designHandle) {
        if (designHandle == null) {
            return;
        }

        String dialect = detectDatabaseDialect();
        log.debug("Runtime SQL Interpolation executing for target dialect: {}", dialect);

        Iterator<?> dataSets = designHandle.getAllDataSets().iterator();
        while (dataSets.hasNext()) {
            Object next = dataSets.next();

            if (next instanceof OdaDataSetHandle odaDataSetHandle) {
                String originalSql = odaDataSetHandle.getQueryText();

                if (StringUtils.isNotBlank(originalSql)) {
                    String processedSql = translateSql(originalSql, dialect);

                    if (!originalSql.equals(processedSql)) {
                        try {
                            odaDataSetHandle.setQueryText(processedSql);
                            log.trace("Successfully interpolated SQL dataset query for dialect optimization.");
                        } catch (SemanticException e) {
                            log.error(
                                    "Failed to set interpolated query text for dataset: {}",
                                    odaDataSetHandle.getName(),
                                    e);
                            throw new PlatformDataIntegrityException(
                                    "error.msg.reporting.birt.sql.interpolation.failed",
                                    "BIRT engine rejected SQL dialect interpolation",
                                    e);
                        }
                    }
                }
            }
        }
    }

    private String translateSql(String sql, String dialect) {
        if (sql == null) return null;

        if ("POSTGRESQL".equalsIgnoreCase(dialect)) {
            sql = GOV_BACKTICK_PATTERN.matcher(sql).replaceAll("");
            sql = MYSQL_IFNULL_PATTERN.matcher(sql).replaceAll("coalesce(");
            sql = sql.replace("FUNC_NULL(", "coalesce(");
        } else if ("MARIADB".equalsIgnoreCase(dialect)) {
            sql = sql.replace("FUNC_NULL(", "ifnull(");
        }

        return sql;
    }

    private String detectDatabaseDialect() {
        // Participate in the existing Spring Transaction context to avoid connection leaks
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            String prodName = connection.getMetaData().getDatabaseProductName();
            if (prodName != null && prodName.toUpperCase().contains("POSTGRES")) {
                return "POSTGRESQL";
            }
            return "MARIADB";
        } catch (Exception e) {
            log.warn("Failed to auto-detect database dialect via metadata. Falling back to POSTGRESQL.", e);
            return "POSTGRESQL";
        } finally {
            // Safely release the connection back to Spring's transaction manager
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
