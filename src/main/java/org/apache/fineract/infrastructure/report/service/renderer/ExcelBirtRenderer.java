/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service.renderer;

import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.report.service.BirtRenderer;
import org.eclipse.birt.report.engine.api.EXCELRenderOption;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

@Component("XLSX")
@RequiredArgsConstructor
public class ExcelBirtRenderer implements BirtRenderer {

    @Override
    public Response render(IRunAndRenderTask task, String reportName) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        EXCELRenderOption options = new EXCELRenderOption();
        boolean isXlsx = "XLSX".equalsIgnoreCase(task.getRenderOption().getOutputFormat());

        options.setOutputFormat(isXlsx ? "xlsx" : "xls");
        options.setOutputStream(baos);

        task.setRenderOption(options);
        task.run();

        String mimeType = isXlsx 
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" 
                : "application/vnd.ms-excel";

        String extension = isXlsx ? "xlsx" : "xls";

        return Response.ok(baos.toByteArray())
                .type(mimeType)
                .header("Content-Disposition", 
                        "attachment; filename=\"" + sanitizeFilename(reportName) + "." + extension + "\"")
                .build();
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}