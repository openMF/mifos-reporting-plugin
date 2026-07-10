/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.renderer;

import jakarta.ws.rs.core.Response;
import org.eclipse.birt.report.engine.api.IReportEngine;

public interface BirtRenderer {
  Response render(IReportEngine reportEngine, String documentPath, String reportName)
      throws Exception;
}
