/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.renderer;

import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.report.config.BirtPluginProperties;
import org.apache.fineract.infrastructure.report.service.BirtRenderer;
import org.apache.fineract.infrastructure.report.util.FilenameUtils;
import org.eclipse.birt.report.engine.api.IPDFRenderOption;
import org.eclipse.birt.report.engine.api.IRenderOption;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.eclipse.birt.report.engine.api.PDFRenderOption;
import org.springframework.stereotype.Component;

@Slf4j
@Component("PDF")
@RequiredArgsConstructor
public class PdfBirtRenderer implements BirtRenderer {

  private final BirtPluginProperties birtProperties;

  @Override
  public Response render(IRunAndRenderTask task, String reportName) throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    // Use PDFRenderOption for advanced PDF features
    PDFRenderOption options = new PDFRenderOption();
    options.setOutputFormat(IRenderOption.OUTPUT_FORMAT_PDF);
    options.setOption(IPDFRenderOption.PAGE_OVERFLOW, IPDFRenderOption.FIT_TO_PAGE_SIZE);
    options.setOutputStream(baos);
    if (StringUtils.isNotBlank(birtProperties.getFontsConfigPath())
        || StringUtils.isNotBlank(birtProperties.getFontsPath())) {

      // Font Embedding Settings
      options.setEmbededFont(true);
      options.setOption(
          PDFRenderOption.PDF_FONT_SUBSTITUTION, Boolean.FALSE); // Prevent font replacement
    }

    task.setRenderOption(options);
    log.debug("Generating PDF report '{}' with font embedding enabled", reportName);
    task.run();

    return Response.ok(baos.toByteArray())
        .type("application/pdf")
        .header(
            "Content-Disposition",
            "attachment; filename=\"" + FilenameUtils.sanitizeFilename(reportName) + ".pdf\"")
        // .header("Cache-Control", "no-cache")
        .build();
  }
}
