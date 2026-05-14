/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service.renderer;

import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.report.service.BirtRenderer;
import org.eclipse.birt.report.engine.api.HTMLRenderOption;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

@Component("HTML")
@RequiredArgsConstructor
public class HtmlBirtRenderer implements BirtRenderer {

    @Override
    public Response render(IRunAndRenderTask task, String reportName) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        HTMLRenderOption options = new HTMLRenderOption();
        options.setOutputFormat("html");
        options.setEmbeddable(true);
        options.setOutputStream(baos);

        task.setRenderOption(options);
        task.run();

        return Response.ok(baos.toByteArray())
                .type("text/html")
                .build();
    }
}