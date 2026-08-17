/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility to translate legacy Pentaho named SQL parameters into BIRT positional parameters and
 * convert MySQL to PostgreSQL.
 */
public final class PentahoSqlTranslator {

    private static final Pattern SQL_TOKEN_PATTERN = Pattern.compile(
            "(/\\*[\\s\\S]*?\\*/)" // Group 1: Block comments
                    + "|(--[^\\n\\r]*)" // Group 2: SQL standard line comments
                    + "|(#[^\\n\\r]*)" // Group 3: MySQL hash line comments
                    + "|('([^'\\\\]|\\\\.)*')" // Group 4: Single quotes
                    + "|(\"([^\"]|\"\")*\")" // Group 6: Double quotes
                    + "|(`([^`]|``)*`)" // Group 8: Backtick identifiers
                    + "|(\\$\\{([^}]+)\\})" // Group 10: Pentaho parameter
            );

    private PentahoSqlTranslator() {}

    /**
     * Translates a legacy Pentaho SQL query into a BIRT-compatible PostgreSQL format. Handles
     * parameter extraction, protects literals/comments from corruption, and automatically converts
     * MySQL-specific syntax (e.g., IF, IFNULL, DATE_ADD, escaped quotes) to Postgres.
     *
     * @param pentahoSql The original Pentaho SQL string.
     * @return A TranslatedQuery containing the transformed SQL and extracted parameter names. Returns
     *     an empty SQL string and empty parameter list if the input is null or blank.
     */
    public static TranslatedQuery translate(String pentahoSql) {
        if (pentahoSql == null || pentahoSql.isBlank()) {
            return new TranslatedQuery("", List.of());
        }
        List<String> parameterNames = new ArrayList<>();
        List<String> protectedTokens = new ArrayList<>();

        String tempSql = extractAndProtectTokens(pentahoSql, parameterNames, protectedTokens);
        String convertedSql = applyDialectTranslation(tempSql);
        String finalSql = restoreTokens(convertedSql, protectedTokens);

        return new TranslatedQuery(finalSql, parameterNames);
    }

    private static String extractAndProtectTokens(String sql, List<String> params, List<String> tokens) {
        Matcher matcher = SQL_TOKEN_PATTERN.matcher(sql);
        StringBuilder tempSql = new StringBuilder();
        int tokenIdx = 0;
        while (matcher.find()) {
            tokenIdx = processTokenMatch(matcher, tempSql, params, tokens, tokenIdx);
        }
        matcher.appendTail(tempSql);
        return tempSql.toString();
    }

    private static int processTokenMatch(
            Matcher m, StringBuilder tempSql, List<String> params, List<String> tokens, int idx) {
        if (m.group(3) != null) {
            tokens.add("--" + m.group(0).substring(1));
        } else if (m.group(8) != null) {
            tokens.add(convertBackticks(m.group(0)));
        } else if (m.group(4) != null) {
            tokens.add(m.group(0).replace("\\'", "''").replace("\\\\", "\\"));
        } else if (m.group(10) != null) {
            return handleParameter(m, tempSql, params, tokens, idx);
        } else {
            tokens.add(m.group(0));
        }
        m.appendReplacement(tempSql, "##TOKEN_" + idx + "##");
        return idx + 1;
    }

    private static String convertBackticks(String match) {
        String unquoted = match.substring(1, match.length() - 1);
        return "\"" + unquoted.replace("``", "`").replace("\"", "\"\"") + "\"";
    }

    private static int handleParameter(
            Matcher m, StringBuilder tempSql, List<String> params, List<String> tokens, int idx) {
        String paramName = m.group(11).strip();
        if (!paramName.isEmpty()) {
            params.add(paramName);
            // FIX: Force clean whitespace around the positional parameter so runtime Regex always matches it
            m.appendReplacement(tempSql, " ? ");
            return idx;
        }
        tokens.add(m.group(0));
        m.appendReplacement(tempSql, "##TOKEN_" + idx + "##");
        return idx + 1;
    }

    private static String applyDialectTranslation(String sql) {
        String converted = sql.replaceAll("(?i)\\bIFNULL\\s*\\(", "COALESCE(");
        converted = translateIfFunctions(converted);
        return converted.replaceAll(
                "(?i)\\bDATE_ADD\\s*\\(\\s*\\?\\s*,\\s*INTERVAL\\s+1\\s+DAY\\s*\\)", "(? + INTERVAL '1 day')");
    }

    private static String translateIfFunctions(String sql) {
        Matcher matcher = Pattern.compile("(?i)\\bIF\\s*\\(").matcher(sql);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            sb.append(sql, lastEnd, matcher.start());
            lastEnd = parseAndConvertIf(sql, matcher.start(), matcher.end(), sb);
            matcher.region(lastEnd, sql.length());
        }
        return sb.append(sql.substring(lastEnd)).toString();
    }

    private static int parseAndConvertIf(String sql, int start, int argStart, StringBuilder sb) {
        List<String> args = new ArrayList<>();
        int j = collectIfArguments(sql, argStart, args);
        if (args.size() == 3) {
            sb.append(String.format(
                    "CASE WHEN %s THEN %s ELSE %s END",
                    translateIfFunctions(args.get(0).trim()),
                    translateIfFunctions(args.get(1).trim()),
                    translateIfFunctions(args.get(2).trim())));
        } else {
            sb.append(sql, start, j);
        }
        return j;
    }

    private static int collectIfArguments(String sql, int start, List<String> args) {
        int depth = 1, j = start, paramStart = start;
        while (j < sql.length() && depth > 0) {
            char c = sql.charAt(j++);
            if (c == '(') depth++;
            else if (c == ')') {
                if (--depth == 0) args.add(sql.substring(paramStart, j - 1));
            } else if (c == ',' && depth == 1) {
                args.add(sql.substring(paramStart, j - 1));
                paramStart = j;
            }
        }
        return j;
    }

    private static String restoreTokens(String sql, List<String> protectedTokens) {
        Matcher matcher = Pattern.compile("##TOKEN_(\\d+)##").matcher(sql);
        StringBuilder finalSql = new StringBuilder();
        while (matcher.find()) {
            int idx = Integer.parseInt(matcher.group(1));
            if (idx < protectedTokens.size()) {
                matcher.appendReplacement(finalSql, Matcher.quoteReplacement(protectedTokens.get(idx)));
            } else {
                matcher.appendReplacement(finalSql, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(finalSql);
        return finalSql.toString();
    }
}
