/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformInternalServerException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.apache.fineract.infrastructure.report.data.BirtReportFileUploadData;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BirtReportUploadService Tests")
class BirtReportUploadServiceTest {

    private static final String TENANT_A = "tenantA";
    private static final String TENANT_B = "tenantB";
    private static final String DESIGN_FILE = "Active Loans.rptdesign";
    private static final String DESIGN_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <report xmlns="http://www.eclipse.org/birt/2005/design" version="3.2.27" id="1">
              <body/>
            </report>
            """;

    @Mock
    private BirtPluginProperties birtProperties;

    @Mock
    private ReportSecurityService reportSecurityService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private BirtReportsDirectory reportsDirectory;
    private BirtReportUploadService uploadService;

    @TempDir
    Path baseDir;

    @BeforeEach
    void setUp() {
        when(birtProperties.getReportsPath()).thenReturn(baseDir.toString());
        reportsDirectory = new BirtReportsDirectory(birtProperties, jdbcTemplate);
        uploadService = new BirtReportUploadService(reportsDirectory, reportSecurityService);
        setTenant(TENANT_A);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.clearTenant();
    }

    private void setTenant(String identifier) {
        ThreadLocalContextUtil.setTenant(
                FineractPlatformTenant.builder().tenantIdentifier(identifier).build());
    }

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private Path designIn(String tenant) {
        return baseDir.resolve(tenant).resolve(DESIGN_FILE);
    }

    @Test
    @DisplayName("Stores a valid design in the authenticated tenant's directory")
    void storesValidDesign() throws Exception {
        BirtReportFileUploadData result = uploadService.upload(DESIGN_FILE, stream(DESIGN_XML));

        assertTrue(Files.isRegularFile(designIn(TENANT_A)));
        assertEquals(DESIGN_XML, Files.readString(designIn(TENANT_A)));
        assertEquals(DESIGN_FILE, result.fileName());
        assertEquals("Active Loans", result.reportName());
        assertFalse(result.overwritten());
        assertEquals(DESIGN_XML.getBytes(StandardCharsets.UTF_8).length, result.size());
    }

    @Test
    @DisplayName("Tenant B's upload leaves Tenant A's design of the same name untouched")
    void tenantsDoNotOverwriteEachOther() throws Exception {
        uploadService.upload(DESIGN_FILE, stream(DESIGN_XML));

        setTenant(TENANT_B);
        String tenantBDesign = DESIGN_XML.replace("<body/>", "<body><label id=\"9\"/></body>");
        uploadService.upload(DESIGN_FILE, stream(tenantBDesign));

        assertEquals(DESIGN_XML, Files.readString(designIn(TENANT_A)));
        assertEquals(tenantBDesign, Files.readString(designIn(TENANT_B)));
    }

    @Test
    @DisplayName("Re-uploading the same name replaces the design and says so")
    void overwritesAnExistingDesign() throws Exception {
        uploadService.upload(DESIGN_FILE, stream(DESIGN_XML));

        String replacement = DESIGN_XML.replace("<body/>", "<body><label id=\"9\"/></body>");
        BirtReportFileUploadData result = uploadService.upload(DESIGN_FILE, stream(replacement));

        assertTrue(result.overwritten());
        assertEquals(replacement, Files.readString(designIn(TENANT_A)));
        try (var entries = Files.list(baseDir.resolve(TENANT_A))) {
            assertEquals(1, entries.count(), "the staged temporary file should not have been left behind");
        }
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @ValueSource(
            strings = {
                "report.pdf",
                "report.xml",
                "report.txt",
                "report.zip",
                "report.json",
                "report.prpt",
                "report",
                ".rptdesign",
                "report.rptdesign.exe"
            })
    @DisplayName("Only .rptdesign is accepted")
    void rejectsOtherExtensions(String fileName) {
        assertThrows(
                PlatformApiDataValidationException.class, () -> uploadService.upload(fileName, stream(DESIGN_XML)));
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @ValueSource(
            strings = {
                "../escaped.rptdesign",
                "../../../etc/escaped.rptdesign",
                "nested/escaped.rptdesign",
                "nested\\escaped.rptdesign",
                "/etc/escaped.rptdesign"
            })
    @DisplayName("A file name carrying a path is rejected and nothing is written")
    void rejectsPathTraversal(String fileName) throws Exception {
        assertThrows(
                PlatformApiDataValidationException.class, () -> uploadService.upload(fileName, stream(DESIGN_XML)));

        try (var entries = Files.walk(baseDir)) {
            assertEquals(1, entries.count(), "nothing should have been written anywhere under the reports directory");
        }
    }

    @Test
    @DisplayName("A file that is not a BIRT design is rejected")
    void rejectsContentThatIsNotADesign() {
        assertThrows(
                PlatformApiDataValidationException.class,
                () -> uploadService.upload(DESIGN_FILE, stream("%PDF-1.7 not xml at all")));
        assertThrows(
                PlatformApiDataValidationException.class,
                () -> uploadService.upload(DESIGN_FILE, stream("<catalog><book/></catalog>")));
        assertFalse(Files.exists(designIn(TENANT_A)));
    }

    @Test
    @DisplayName("A design declaring a doctype is rejected rather than resolved")
    void rejectsDoctypeDeclarations() {
        String withDoctype = "<?xml version=\"1.0\"?><!DOCTYPE report [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<report xmlns=\"http://www.eclipse.org/birt/2005/design\"><body>&x;</body></report>";

        assertThrows(
                PlatformApiDataValidationException.class, () -> uploadService.upload(DESIGN_FILE, stream(withDoctype)));
    }

    @Test
    @DisplayName("An empty file is rejected")
    void rejectsAnEmptyFile() {
        assertThrows(PlatformApiDataValidationException.class, () -> uploadService.upload(DESIGN_FILE, stream("")));
    }

    @Test
    @DisplayName("A design over the size limit is rejected on what is read, not on a declared length")
    void rejectsAnOversizedDesign() {
        String oversized = "<report>" + "x".repeat((int) BirtReportUploadService.MAX_DESIGN_SIZE_BYTES) + "</report>";

        assertThrows(
                PlatformApiDataValidationException.class, () -> uploadService.upload(DESIGN_FILE, stream(oversized)));
        assertFalse(Files.exists(designIn(TENANT_A)));
    }

    @Test
    @DisplayName("A user without CREATE_REPORT is refused before the file is read")
    void refusesAnUnauthorisedUser() {
        doThrow(new NoAuthorizationException("The authenticated user does not have the CREATE_REPORT permission."))
                .when(reportSecurityService)
                .checkCreateReportPermission();

        assertThrows(NoAuthorizationException.class, () -> uploadService.upload(DESIGN_FILE, stream(DESIGN_XML)));
        assertFalse(Files.exists(baseDir.resolve(TENANT_A)));
    }

    @Test
    @DisplayName("Authorization is checked before anything else")
    void checksAuthorizationFirst() {
        doThrow(new NoAuthorizationException("nope"))
                .when(reportSecurityService)
                .checkCreateReportPermission();

        assertThrows(NoAuthorizationException.class, () -> uploadService.upload("report.pdf", stream("junk")));
    }

    @Test
    @DisplayName("A storage failure surfaces as a server error, not as a success")
    void reportsAStorageFailure() throws Exception {
        // A regular file where the tenant directory has to go: the directory cannot be created.
        Files.writeString(baseDir.resolve(TENANT_A), "in the way");

        assertThrows(
                PlatformInternalServerException.class, () -> uploadService.upload(DESIGN_FILE, stream(DESIGN_XML)));
    }

    @Test
    @DisplayName("A failing stream surfaces as a server error and writes nothing")
    void reportsAFailingStream() throws Exception {
        InputStream failing = new InputStream() {

            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        };

        assertThrows(PlatformInternalServerException.class, () -> uploadService.upload(DESIGN_FILE, failing));
        assertFalse(Files.exists(designIn(TENANT_A)));
    }

    @Test
    @DisplayName("Nothing is written when the file name is missing")
    void rejectsAMissingFileName() {
        assertThrows(PlatformApiDataValidationException.class, () -> uploadService.upload(null, stream(DESIGN_XML)));
        verify(reportSecurityService).checkCreateReportPermission();
    }

    @Test
    @DisplayName("Nothing is written when no file part is present")
    void rejectsAMissingFile() {
        assertThrows(PlatformApiDataValidationException.class, () -> uploadService.upload(DESIGN_FILE, null));
        assertFalse(Files.exists(designIn(TENANT_A)));
        assertFalse(Files.exists(baseDir.resolve(TENANT_A)), "no tenant directory should have been created");
    }
}
