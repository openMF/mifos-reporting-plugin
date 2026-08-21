/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Guards the SQL that ships with the plugin.
 *
 * <p>Report SQL runs on a read-only connection, so a shipped report cannot damage the database. What
 * these tests protect is the other half: that the shipped designs keep binding their values as
 * parameters instead of pasting them into the query, and that they keep filtering on the caller's
 * office hierarchy — neither of which the execution boundary can impose on a report design.
 */
@DisplayName("Shipped .rptdesign files")
class ShippedReportDesignTest {

    private static final Path REPORTS_DIRECTORY = Paths.get("birt", "reports");

    private static final Pattern QUERY_TEXT =
            Pattern.compile("<xml-property name=\"queryText\"><!\\[CDATA\\[(.*?)]]></xml-property>", Pattern.DOTALL);

    static Stream<Path> shippedReports() throws IOException {
        try (Stream<Path> files = Files.list(REPORTS_DIRECTORY)) {
            return files.filter(path -> path.toString().endsWith(".rptdesign")).toList().stream();
        }
    }

    @Test
    @DisplayName("are present, so the checks below are not silently vacuous")
    void reportsArePresent() throws IOException {
        assertThat(shippedReports()).isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shippedReports")
    @DisplayName("build every query from bound parameters, never string interpolation")
    void queriesAreParameterised(Path report) {
        final String design = read(report);

        assertThat(queriesOf(design)).as("no dataset query found in %s", report).isNotEmpty();

        for (String query : queriesOf(design)) {
            assertThat(query)
                    .as("query in %s interpolates a value into the SQL text", report)
                    .doesNotContain("${");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shippedReports")
    @DisplayName("do not rewrite their query at run time from a script")
    void queriesAreNotBuiltByScripts(Path report) {
        final String design = read(report);

        assertThat(design)
                .as(
                        "a script in %s assigns queryText, which would make the executed SQL "
                                + "invisible to any design-time inspection",
                        report)
                .doesNotContain("this.queryText")
                .doesNotContain("setQueryText");
    }

    @Test
    @DisplayName("Active_Loans_Details scopes its result set to the caller's office hierarchy")
    void activeLoansDetailsBindsTheUserHierarchy() {
        final String design = read(REPORTS_DIRECTORY.resolve("Active_Loans_Details.rptdesign"));

        assertThat(design)
                .as("userhierarchy must stay a declared report parameter for the server to inject it")
                .contains("<scalar-parameter name=\"userhierarchy\"");

        assertThat(design)
                .as("userhierarchy must stay bound as the dataset's first parameter")
                .containsPattern(Pattern.compile(
                        "<property name=\"paramName\">userhierarchy</property>.*?"
                                + "<property name=\"position\">1</property>",
                        Pattern.DOTALL));

        final String query = queriesOf(design).get(0);
        assertThat(query)
                .as("the hierarchy filter must be a bind, not a literal")
                .contains("ounder.hierarchy LIKE CONCAT(?, '%')");
    }

    /**
     * A tripwire, not a preference. Rejecting SQL comments is a recurring proposal for hardening
     * report SQL; it would break this report on the day it is introduced, and rejecting comments
     * stops no read-based injection anyway.
     */
    @Test
    @DisplayName("Active_Loans_Details contains an SQL comment, so comment rejection would break it")
    void activeLoansDetailsContainsAnSqlComment() {
        final String query = queriesOf(read(REPORTS_DIRECTORY.resolve("Active_Loans_Details.rptdesign")))
                .get(0);

        assertThat(query).contains("-- Param 2: branch");
    }

    private List<String> queriesOf(String design) {
        final List<String> queries = new ArrayList<>();
        final Matcher matcher = QUERY_TEXT.matcher(design);
        while (matcher.find()) {
            queries.add(matcher.group(1));
        }
        return queries;
    }

    private String read(Path report) {
        try {
            return Files.readString(report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
