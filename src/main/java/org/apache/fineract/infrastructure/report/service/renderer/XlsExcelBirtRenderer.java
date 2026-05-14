package org.apache.fineract.infrastructure.report.service.renderer;

import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.report.service.BirtRenderer;
import org.eclipse.birt.report.engine.api.EXCELRenderOption;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

@Component("XLS")
@RequiredArgsConstructor
public class XlsExcelBirtRenderer implements BirtRenderer {

    @Override
    public Response render(IRunAndRenderTask task, String reportName) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        EXCELRenderOption options = new EXCELRenderOption();        
        String outputFormat = "xls";
        options.setOutputFormat(outputFormat);
        options.setOutputStream(baos);

        task.setRenderOption(options);
        task.run();

        String mimeType = "application/vnd.ms-excel";
        String extension = "xls";

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