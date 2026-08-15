/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.model;

/** Intermediate Representation (IR) of a Pentaho report parameter. */
public record PentahoParameter(
        String name, String type, boolean isMandatory, String defaultValue, boolean isList, String queryName) {}
