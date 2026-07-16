/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.model;

import java.util.List;

/** The Intermediate Representation (IR) of a complete Pentaho Report. */
public record PentahoReportModel(
    String reportName,
    List<PentahoSqlDataset> datasets,
    List<PentahoParameter> parameters,
    List<PentahoReportModel> subreports) {}
