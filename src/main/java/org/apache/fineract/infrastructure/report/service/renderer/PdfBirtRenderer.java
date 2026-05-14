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
import org.eclipse.birt.report.engine.api.IPDFRenderOption;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.eclipse.birt.report.engine.api.PDFRenderOption;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

@Component("PDF")
@RequiredArgsConstructor
public class PdfBirtRenderer implements BirtRenderer {

    @Override
    public Response render(IRunAndRenderTask task, String reportName) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        PDFRenderOption options = new PDFRenderOption();
        options.setOutputFormat("pdf");
        options.setOption(IPDFRenderOption.PAGE_OVERFLOW, IPDFRenderOption.FIT_TO_PAGE_SIZE);
        options.setOutputStream(baos);

        task.setRenderOption(options);
        task.run();

        return Response.ok(baos.toByteArray())
                .type("application/pdf")
                .build();
    }
}