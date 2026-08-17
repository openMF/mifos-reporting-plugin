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

@Slf4j
@Component
@RequiredArgsConstructor
public class BirtSqlDialectInterpolator {

    private final DataSource dataSource;

    private static final Pattern MYSQL_IFNULL_PATTERN = Pattern.compile("(?i)\\bifnull\\s*\\(");
    private static final Pattern GOV_BACKTICK_PATTERN = Pattern.compile("`");
    private static final Pattern MYSQL_SINGLE_QUOTE_ALIAS_PATTERN = Pattern.compile("(?i)\\bAS\\s+'([^']+)'");

    // Explicitly targets =, >, < comparisons for CASTing to fix BigInt mismatches
    private static final Pattern POSTGRES_ID_PARAM_PATTERN =
            Pattern.compile("(?i)(\\b[a-zA-Z0-9_]*id\\b\\s*(?:=|<>|>|<|>=|<=)\\s*)(\\?)");

    public void interpolate(ReportDesignHandle designHandle) {
        if (designHandle == null) return;
        String dialect = detectDatabaseDialect();

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
                        } catch (SemanticException e) {
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
            sql = MYSQL_SINGLE_QUOTE_ALIAS_PATTERN.matcher(sql).replaceAll("AS \"$1\"");

            // Cast single ID parameters for Postgres strict typing
            sql = POSTGRES_ID_PARAM_PATTERN.matcher(sql).replaceAll("$1CAST($2 AS bigint)");

            // Translate legacy MySQL DATE_ADD function
            sql = sql.replaceAll(
                    "(?i)\\bDATE_ADD\\s*\\(\\s*\\?\\s*,\\s*INTERVAL\\s+([0-9]+)\\s+DAY\\s*\\)",
                    "(? + INTERVAL '$1 day')");

            // Neutralize MySQL inline variable assignments
            sql = sql.replaceAll(
                    "(?i)\\(\\s*SELECT\\s+@[a-zA-Z0-9_]+\\s*:=\\s*0(?:\\.0)?\\s*\\)\\s*AS\\s+[a-zA-Z0-9_]+",
                    "(SELECT 0.0) AS init_var");
            sql = sql.replaceAll(
                    "(?i)@[a-zA-Z0-9_]+\\s*:=\\s*@[a-zA-Z0-9_]+\\s*(?:\\+|-)\\s*[^\\s]+\\s+AS\\s+[a-zA-Z0-9_]+",
                    "0.0 AS running_balance");
            sql = sql.replaceAll("(?i)@[a-zA-Z0-9_]+\\s*:=\\s*[^\\s,)]+", "0.0");
            sql = sql.replaceAll("(?i)@[a-zA-Z0-9_]+", "0.0");
        } else if ("MARIADB".equalsIgnoreCase(dialect)) {
            sql = sql.replace("FUNC_NULL(", "ifnull(");
        }
        return sql;
    }

    private String detectDatabaseDialect() {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            String prodName = connection.getMetaData().getDatabaseProductName();
            if (prodName != null && prodName.toUpperCase().contains("POSTGRES")) {
                return "POSTGRESQL";
            }
            return "MARIADB";
        } catch (Exception e) {
            return "POSTGRESQL";
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
