/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.renderer;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.OutputStream;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.apache.fineract.infrastructure.report.util.FilenameUtils;
import org.eclipse.birt.report.engine.api.IPDFRenderOption;
import org.eclipse.birt.report.engine.api.IRenderOption;
import org.eclipse.birt.report.engine.api.PDFRenderOption;
import org.springframework.stereotype.Component;

/** Renderer for exporting BIRT outputs to PDF format with embedded fonts. */
@Component("PDF")
@RequiredArgsConstructor
public class PdfBirtRenderer extends AbstractBirtRenderer {

    private final BirtPluginProperties birtProperties;

    @Override
    protected IRenderOption createRenderOption(OutputStream output, String reportName) {
        PDFRenderOption options = new PDFRenderOption();
        options.setOutputFormat(IRenderOption.OUTPUT_FORMAT_PDF);
        options.setOption(IPDFRenderOption.PAGE_OVERFLOW, IPDFRenderOption.FIT_TO_PAGE_SIZE);
        options.setOutputStream(output);

        if (StringUtils.isNotBlank(birtProperties.getFontsConfigPath())
                || StringUtils.isNotBlank(birtProperties.getFontsPath())) {
            options.setEmbededFont(true);
            options.setOption(PDFRenderOption.PDF_FONT_SUBSTITUTION, Boolean.FALSE);
        }
        return options;
    }

    @Override
    protected Response buildResponse(StreamingOutput stream, String reportName) {
        return Response.ok(stream)
                .type("application/pdf")
                .header(
                        "Content-Disposition",
                        "attachment; filename=\"" + FilenameUtils.sanitizeFilename(reportName) + ".pdf\"")
                .build();
    }
}
