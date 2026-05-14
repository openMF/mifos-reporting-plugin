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
import org.apache.fineract.infrastructure.report.util.FilenameUtils;
import org.eclipse.birt.report.engine.api.EXCELRenderOption;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.springframework.stereotype.Component;

@Component("XLSX")
@RequiredArgsConstructor
public class XlsxExcelBirtRenderer implements BirtRenderer {

  @Override
  public Response render(IRunAndRenderTask task, String reportName) throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    EXCELRenderOption options = new EXCELRenderOption();
    String outputFormat = "xlsx";

    options.setOutputFormat(outputFormat);
    options.setOutputStream(baos);

    task.setRenderOption(options);
    task.run();

    String mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    String extension = "xlsx";

    return Response.ok(baos.toByteArray())
        .type(mimeType)
        .header(
            "Content-Disposition",
            "attachment; filename=\""
                + FilenameUtils.sanitizeFilename(reportName)
                + "."
                + extension
                + "\"")
        .build();
  }
}
