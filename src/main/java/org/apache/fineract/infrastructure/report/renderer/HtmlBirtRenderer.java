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
import org.eclipse.birt.report.engine.api.HTMLRenderOption;
import org.eclipse.birt.report.engine.api.IRenderOption;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.springframework.stereotype.Component;

@Component("HTML")
@RequiredArgsConstructor
public class HtmlBirtRenderer implements BirtRenderer {

  @Override
  public Response render(IRunAndRenderTask task, String reportName) throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    HTMLRenderOption options = new HTMLRenderOption();
    options.setOutputFormat(IRenderOption.OUTPUT_FORMAT_HTML);
    options.setEmbeddable(true);
    options.setOutputStream(baos);

    task.setRenderOption(options);
    task.run();

    return Response.ok(baos.toByteArray()).type("text/html").build();
  }
}
