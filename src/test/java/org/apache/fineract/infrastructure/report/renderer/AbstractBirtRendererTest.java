/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.renderer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import org.eclipse.birt.report.engine.api.IRenderOption;
import org.eclipse.birt.report.engine.api.IRenderTask;
import org.eclipse.birt.report.engine.api.IReportDocument;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.RenderOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractBirtRenderer TDD Tests")
class AbstractBirtRendererTest {

    @Mock
    private IReportEngine reportEngine;

    @Mock
    private IReportDocument reportDocument;

    @Mock
    private IRenderTask renderTask;

    // Concrete dummy class for testing the abstract logic
    private static class DummyRenderer extends AbstractBirtRenderer {
        @Override
        protected IRenderOption createRenderOption(OutputStream output, String reportName) {
            return new RenderOption();
        }

        @Override
        protected Response buildResponse(StreamingOutput stream, String reportName) {
            return Response.ok(stream).build();
        }
    }

    @Test
    @DisplayName("Should successfully execute the streaming render lifecycle")
    void shouldExecuteStreamingLifecycle() throws Exception {
        DummyRenderer renderer = new DummyRenderer();
        when(reportEngine.openReportDocument(anyString())).thenReturn(reportDocument);
        when(reportEngine.createRenderTask(reportDocument)).thenReturn(renderTask);

        Response response = renderer.render(reportEngine, "/dummy/path.rptdocument", "TestReport");

        assertNotNull(response);

        // Simulate JAX-RS executing the stream
        StreamingOutput stream = (StreamingOutput) response.getEntity();
        stream.write(new ByteArrayOutputStream());

        verify(renderTask).setRenderOption(any());
        verify(renderTask).render();
        verify(renderTask).close();
        verify(reportDocument).close();
    }

    @Test
    @DisplayName("Should prevent memory leak by closing document if render task creation fails")
    void shouldCloseDocumentIfTaskCreationFails() throws Exception {
        DummyRenderer renderer = new DummyRenderer();
        when(reportEngine.openReportDocument(anyString())).thenReturn(reportDocument);

        // Simulate a failure during task creation
        doThrow(new RuntimeException("BIRT Engine Error")).when(reportEngine).createRenderTask(reportDocument);

        assertThrows(
                RuntimeException.class, () -> renderer.render(reportEngine, "/dummy/path.rptdocument", "TestReport"));

        // Critical TDD assertion: Ensure the document was safely closed to prevent the memory leak!
        verify(reportDocument).close();
    }
}
