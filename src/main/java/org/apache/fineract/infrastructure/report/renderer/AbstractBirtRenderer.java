/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.renderer;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.birt.report.engine.api.IRenderOption;
import org.eclipse.birt.report.engine.api.IRenderTask;
import org.eclipse.birt.report.engine.api.IReportDocument;
import org.eclipse.birt.report.engine.api.IReportEngine;

/**
 * Abstract base class for BIRT renderers. Encapsulates the streaming lifecycle, resource
 * management, and temporary document cleanup to ensure safe execution.
 */
@Slf4j
public abstract class AbstractBirtRenderer implements BirtRenderer {

  @Override
  public Response render(IReportEngine reportEngine, String documentPath, String reportName)
      throws Exception {

    final IReportDocument document = reportEngine.openReportDocument(documentPath);
    final IRenderTask renderTask;

    // Prevent document memory leak if task creation fails
    try {
      renderTask = reportEngine.createRenderTask(document);
    } catch (Exception e) {
      closeQuietly(document::close);
      throw e;
    }

    StreamingOutput stream =
        output -> {
          try {
            IRenderOption options = createRenderOption(output, reportName);
            renderTask.setRenderOption(options);
            log.debug(
                "Streaming report '{}' using {}", reportName, this.getClass().getSimpleName());
            renderTask.render();

          } catch (Exception e) {
            log.error("Error streaming BIRT report", e);
            throw new WebApplicationException("Failed to stream report", e);
          } finally {
            closeQuietly(renderTask::close);
            closeQuietly(document::close);
            deleteTempFileQuietly(documentPath);
          }
        };

    return buildResponse(stream, reportName);
  }

  /** Subclasses must provide the specific RenderOption configuration for their format. */
  protected abstract IRenderOption createRenderOption(OutputStream output, String reportName)
      throws Exception;

  /**
   * Subclasses must wrap the StreamingOutput into a JAX-RS Response with appropriate MIME types and
   * headers.
   */
  protected abstract Response buildResponse(StreamingOutput stream, String reportName);

  // --- PRIVATE HELPER METHODS BELOW ---

  private void deleteTempFileQuietly(String documentPath) {
    try {
      Files.deleteIfExists(Paths.get(documentPath));
    } catch (java.io.IOException e) {
      log.warn("Failed to delete temporary BIRT document: {}", documentPath, e);
    }
  }

  /** Functional interface to handle BIRT resources that throw exceptions on close */
  @FunctionalInterface
  private interface BirtResource {
    void close() throws Exception;
  }

  private void closeQuietly(BirtResource resource) {
    try {
      if (resource != null) {
        resource.close();
      }
    } catch (Exception e) {
      log.warn("Error closing BIRT resource", e);
    }
  }
}
