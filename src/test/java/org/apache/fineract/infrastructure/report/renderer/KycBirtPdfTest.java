/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.renderer;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.core.StreamingOutput;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.apache.fineract.infrastructure.report.service.KycReportDataService;
import org.eclipse.birt.core.framework.Platform;
import org.eclipse.birt.report.engine.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

class KycBirtPdfTest {
    private static IReportEngine engine;

    @TempDir
    Path temp;

    @BeforeAll
    static void startEngine() throws Exception {
        var config = new EngineConfig();
        var fonts = new BirtPluginProperties();
        fonts.setFontsPath(Path.of("birt/fonts").toAbsolutePath().toString());
        new org.apache.fineract.infrastructure.report.service.BirtFontConfigurer(fonts).configureFonts(config);
        config.setLogConfig(null, Level.WARNING);
        Platform.startup(config);
        var factory = (IReportEngineFactory)
                Platform.createFactoryObject(IReportEngineFactory.EXTENSION_REPORT_ENGINE_FACTORY);
        engine = factory.createReportEngine(config);
    }

    @AfterAll
    static void stopEngine() {
        if (engine != null) engine.destroy();
        Platform.shutdown();
    }

    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @ValueSource(strings = {"KYCNaturalPerson", "KYCLegalPerson"})
    void generatesParseablePdfUsingTheExistingBirtRenderer(String report) throws Exception {
        var design = engine.openReportDesign("birt/reports/" + report + ".rptdesign");
        var document = temp.resolve(report + ".rptdocument");
        var task = engine.createRunTask(design);
        try {
            task.setErrorHandlingOption(IEngineTask.CANCEL_ON_ERROR);
            task.setParameterValue("clientId", "7");
            task.getAppContext()
                    .put(
                            KycReportDataService.APP_CONTEXT_KEY,
                            List.of(
                                    Map.of(
                                            "section",
                                            "Client information",
                                            "label",
                                            "Client name",
                                            "value",
                                            "María Müller <script>alert(1)</script>"),
                                    Map.of(
                                            "section",
                                            "External KYC verification",
                                            "label",
                                            "Latest saved status",
                                            "value",
                                            "Not recorded"),
                                    Map.of(
                                            "section",
                                            "Document validation",
                                            "label",
                                            "Saved validation status",
                                            "value",
                                            "INCOMPLETE")));
            task.run(document.toString());
            assertTrue(task.getErrors().isEmpty(), () -> task.getErrors().toString());
        } finally {
            task.close();
        }
        var properties = new BirtPluginProperties();
        properties.setFontsPath(Path.of("birt/fonts").toAbsolutePath().toString());
        var renderer = new PdfBirtRenderer(properties);
        var output = new ByteArrayOutputStream();
        try (var response = renderer.render(engine, document.toString(), report)) {
            assertEquals("application/pdf", response.getMediaType().toString());
            ((StreamingOutput) response.getEntity()).write(output);
        }
        byte[] pdf = output.toByteArray();
        assertTrue(new String(pdf, 0, 8, StandardCharsets.US_ASCII).startsWith("%PDF-"));
        try (var reader = new PdfReader(pdf)) {
            assertTrue(reader.getNumberOfPages() > 0);
            var fonts = reader.getPageN(1)
                    .getAsDict(org.openpdf.text.pdf.PdfName.RESOURCES)
                    .getAsDict(org.openpdf.text.pdf.PdfName.FONT);
            assertTrue(
                    fonts.getKeys().stream()
                            .anyMatch(key -> fonts.getAsDict(key)
                                    .getAsName(org.openpdf.text.pdf.PdfName.BASEFONT)
                                    .toString()
                                    .contains("NotoSans")),
                    "The configured Unicode font must be used by the PDF emitter: "
                            + fonts.getKeys().stream()
                                    .map(key -> fonts.getAsDict(key)
                                            .getAsName(org.openpdf.text.pdf.PdfName.BASEFONT)
                                            .toString())
                                    .toList());
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertTrue(text.contains(report.equals("KYCLegalPerson") ? "ENTITY" : "PERSON"), text);
            assertTrue(text.contains("María Müller"), text);
            assertTrue(text.contains("<script>alert(1)</script>"), text);
            assertTrue(text.contains("Not recorded"), text);
            assertTrue(text.contains("INCOMPLETE"), text);
            assertFalse(text.contains("APPROVED"), text);
        }
        assertFalse(Files.exists(document), "Sensitive temporary report document must be deleted after streaming");
    }
}
