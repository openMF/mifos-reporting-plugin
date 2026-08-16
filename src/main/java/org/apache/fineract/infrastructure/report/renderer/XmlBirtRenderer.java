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
import org.eclipse.birt.report.engine.api.IRenderOption;
import org.eclipse.birt.report.engine.api.RenderOption;
import org.springframework.stereotype.Component;

/**
 * Renderer for exporting BIRT outputs to XML format.
 * Registered under the bean name "XML" so that the Map&lt;String, BirtRenderer&gt;
 * injected into BirtReportingProcessServiceImpl resolves it automatically.
 * Fully multi-tenant aware – relies on the already-injected tenant JDBC connection
 * that was set on the IRunTask by BirtReportingProcessServiceImpl.
 */
@Component("XML")
public class XmlBirtRenderer extends AbstractBirtRenderer {

    @Override
    protected IRenderOption createRenderOption(OutputStream output, String reportName) {
        RenderOption options = new RenderOption();
        // BIRT native XML emitter
        options.setOutputFormat("xml");
        options.setOutputStream(output);
        return options;
    }

    @Override
    protected Response buildResponse(StreamingOutput stream, String reportName) {
        return Response.ok(stream)
                .type("application/xml")
                .header(
                        "Content-Disposition",
                        "attachment; filename=\"" + FilenameUtils.sanitizeFilename(reportName) + ".xml\"")
                .build();
    }
}
