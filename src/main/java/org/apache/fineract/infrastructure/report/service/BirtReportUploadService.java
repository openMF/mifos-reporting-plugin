/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformInternalServerException;
import org.apache.fineract.infrastructure.report.data.BirtReportFileUploadData;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

/**
 * Stores an uploaded Eclipse BIRT® report design in the authenticated tenant's reports directory.
 *
 * <p>The caller supplies a file and nothing else. Where the file goes is decided here, from the
 * tenant Apache Fineract® authenticated the request as, so no request can reach another tenant's
 * directory or anywhere outside the reports tree.
 *
 * <p>Nothing evicts the design cache: {@code BirtReportExecutionFactory} already calls
 * {@link BirtReportLoader#validateTemplateFreshness} before every run, which notices the changed
 * modification time and evicts the stale entry itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BirtReportUploadService {

    /** The extension Eclipse BIRT® report designs carry, and the only one accepted. */
    public static final String DESIGN_EXTENSION = ".rptdesign";

    /**
     * Matches the limit Apache Fineract® applies to its own document uploads
     * ({@code ContentRepository.MAX_FILE_UPLOAD_SIZE_IN_MB}). A report design is XML and normally
     * well under a megabyte; the limit is here to stop a client streaming without end, not to size
     * real designs.
     */
    public static final long MAX_DESIGN_SIZE_BYTES = 5L * 1024 * 1024;

    private static final String PARAMETER = "file";
    private static final String RESOURCE = "birtreportfile";
    private static final String DESIGN_ROOT_ELEMENT = "report";
    private static final String DESIGN_NAMESPACE = "http://www.eclipse.org/birt/2005/design";

    private final BirtReportsDirectory reportsDirectory;
    private final ReportSecurityService reportSecurityService;

    public BirtReportFileUploadData upload(final String submittedFileName, final InputStream content) {

        reportSecurityService.checkCreateReportPermission();

        final String fileName = validatedFileName(submittedFileName);
        final byte[] design = readBounded(content, fileName);
        validateReportDesign(design, fileName);

        final Path target = destination(fileName);
        final boolean overwritten = Files.isRegularFile(target);
        write(design, target, fileName);

        log.info(
                "BIRT report design uploaded | tenant={} | file={} | bytes={} | overwrote={}",
                reportsDirectory.tenantIdentifier(),
                fileName,
                design.length,
                overwritten);

        return new BirtReportFileUploadData(fileName, design.length, overwritten, reportNameOf(fileName));
    }

    /**
     * A multipart part carries whatever name the client chose, so the name is checked rather than
     * repaired. {@link org.apache.fineract.infrastructure.report.util.FilenameUtils#sanitizeFilename}
     * is deliberately not used here: it rewrites every character outside {@code [A-Za-z0-9_.-]},
     * which would silently turn {@code Active Loans.rptdesign} into a design no report of that name
     * can ever load.
     */
    private String validatedFileName(final String submittedFileName) {
        if (StringUtils.isBlank(submittedFileName)) {
            throw validationError("filename.missing", "No report file name was supplied.");
        }

        final String fileName = submittedFileName.trim();

        if (StringUtils.containsAny(fileName, '/', '\\', '\0') || fileName.contains("..")) {
            log.warn("Rejected BIRT report design upload with a path in its name: {}", fileName);
            throw validationError("filename.invalid", "A report file name may not contain a path: " + fileName);
        }

        if (!fileName.toLowerCase(Locale.ROOT).endsWith(DESIGN_EXTENSION)
                || fileName.length() == DESIGN_EXTENSION.length()) {
            throw validationError(
                    "extension.invalid",
                    "Only Eclipse BIRT report designs (" + DESIGN_EXTENSION + ") may be uploaded, but got: "
                            + fileName);
        }

        return fileName;
    }

    /**
     * Reads the whole design, refusing anything over the limit. The limit is enforced on what is
     * actually read rather than on a {@code Content-Length} the client controls, so an oversized
     * body cannot be smuggled past it by understating its size.
     */
    private byte[] readBounded(final InputStream content, final String fileName) {
        if (content == null) {
            throw validationError("file.missing", "No report file was supplied.");
        }
        try (BufferedInputStream in = new BufferedInputStream(content)) {
            final byte[] design = in.readNBytes((int) MAX_DESIGN_SIZE_BYTES + 1);

            if (design.length == 0) {
                throw validationError("file.empty", "The uploaded report file is empty: " + fileName);
            }
            if (design.length > MAX_DESIGN_SIZE_BYTES) {
                throw validationError(
                        "file.too.large",
                        "The uploaded report file exceeds " + MAX_DESIGN_SIZE_BYTES + " bytes: " + fileName);
            }
            return design;
        } catch (IOException e) {
            throw new PlatformInternalServerException(
                    "error.msg.reporting.upload.read.failed",
                    "Failed to read the uploaded report file: " + fileName,
                    fileName);
        }
    }

    /**
     * Confirms the bytes are a report design rather than something renamed to look like one. The
     * parser is the hardened one the plugin already uses for report XML — no doctype, no external
     * entities — because this content is untrusted until it has been read.
     *
     * <p>This is a shape check, not a schema check: it stops a PDF, an archive or an unrelated
     * document from being installed as a report, and leaves the rest to Eclipse BIRT® itself.
     */
    private void validateReportDesign(final byte[] design, final String fileName) {
        final Element root;
        try {
            root = hardenedDocumentBuilderFactory()
                    .newDocumentBuilder()
                    .parse(new ByteArrayInputStream(design))
                    .getDocumentElement();
        } catch (Exception e) {
            log.debug("Uploaded report file {} could not be parsed as XML", fileName, e);
            throw validationError(
                    "design.invalid", "The uploaded file is not a readable Eclipse BIRT report design: " + fileName);
        }

        final boolean isReportElement = root != null && DESIGN_ROOT_ELEMENT.equals(root.getLocalName());
        /*
         * A design written by the BIRT designer declares the design namespace,
         * but one hand-written without it still opens, so an absent namespace
         * is accepted and a wrong one is not.
         */
        final boolean namespaceIsBirt =
                root != null && (root.getNamespaceURI() == null || DESIGN_NAMESPACE.equals(root.getNamespaceURI()));

        if (!isReportElement || !namespaceIsBirt) {
            throw validationError(
                    "design.invalid", "The uploaded file is not an Eclipse BIRT report design: " + fileName);
        }
    }

    private DocumentBuilderFactory hardenedDocumentBuilderFactory() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException e) {
            log.debug("JAXP properties ACCESS_EXTERNAL_DTD/SCHEMA not supported by the XML parser. Ignoring.");
        }

        return factory;
    }

    private Path destination(final String fileName) {
        try {
            return reportsDirectory.resolveForWrite(fileName);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected BIRT report design upload resolving outside the tenant directory: {}", fileName);
            throw validationError("filename.invalid", "A report file name may not contain a path: " + fileName);
        } catch (IOException e) {
            log.error("Failed to open the reports directory for {}", fileName, e);
            throw new PlatformInternalServerException(
                    "error.msg.reporting.upload.storage.failed",
                    "Failed to open the reports directory for: " + fileName,
                    fileName);
        }
    }

    /**
     * Writes beside the destination and moves into place, so an upload that fails part way through
     * cannot leave a half-written design where a working one used to be. The move replaces an
     * existing design of the same name, which is how overwriting works.
     *
     * <p>{@code ATOMIC_MOVE} makes {@code REPLACE_EXISTING} implementation-defined, so a provider is
     * free to refuse an existing target instead of replacing it. Overwriting is part of the contract
     * here, so that refusal falls back to the plain replacing move rather than reaching the caller
     * as a storage failure.
     */
    private void write(final byte[] design, final Path target, final String fileName) {
        Path staged = null;
        try {
            staged = Files.createTempFile(target.getParent(), ".upload-", DESIGN_EXTENSION);
            Files.write(staged, design);
            try {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                log.debug("Atomic replace of {} was refused. Falling back to a non-atomic replace.", fileName, e);
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
            staged = null;
        } catch (IOException e) {
            log.error("Failed to store the report design {}", fileName, e);
            throw new PlatformInternalServerException(
                    "error.msg.reporting.upload.storage.failed",
                    "Failed to store the report design: " + fileName,
                    fileName);
        } finally {
            deleteQuietly(staged);
        }
    }

    private void deleteQuietly(final Path staged) {
        if (staged == null) {
            return;
        }
        try {
            Files.deleteIfExists(staged);
        } catch (IOException e) {
            log.warn("Failed to remove the staged report design {}", staged, e);
        }
    }

    private String reportNameOf(final String fileName) {
        return fileName.substring(0, fileName.length() - DESIGN_EXTENSION.length());
    }

    private PlatformApiDataValidationException validationError(final String code, final String message) {
        final ApiParameterError error =
                ApiParameterError.parameterError("validation.msg." + RESOURCE + "." + code, message, PARAMETER);
        return new PlatformApiDataValidationException(
                "validation.msg.validation.errors.exist", "Validation errors exist.", List.of(error));
    }
}
