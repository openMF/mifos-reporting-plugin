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
import org.apache.fineract.infrastructure.report.util.FilenameUtils;
import org.eclipse.birt.report.engine.api.EXCELRenderOption;
import org.eclipse.birt.report.engine.api.IRenderOption;
import org.springframework.stereotype.Component;

/** Renderer for exporting BIRT outputs to legacy Excel (XLS) format. */
@Component("XLS")
public class XlsExcelBirtRenderer extends AbstractBirtRenderer {

  @Override
  protected IRenderOption createRenderOption(OutputStream output, String reportName) {
    EXCELRenderOption options = new EXCELRenderOption();
    options.setOutputFormat("xls");
    options.setOutputStream(output);
    return options;
  }

  @Override
  protected Response buildResponse(StreamingOutput stream, String reportName) {
    return Response.ok(stream)
        .type("application/vnd.ms-excel")
        .header(
            "Content-Disposition",
            "attachment; filename=\"" + FilenameUtils.sanitizeFilename(reportName) + ".xls\"")
        .build();
  }
}
