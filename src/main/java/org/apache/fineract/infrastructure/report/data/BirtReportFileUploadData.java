/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.data;

/**
 * What a successful report design upload tells the caller.
 *
 * <p>Deliberately says nothing about where the file landed: the destination is the server's to
 * decide and the client has no use for it.
 *
 * @param fileName the name the design was stored under
 * @param size its size in bytes
 * @param overwritten whether a design of the same name was replaced
 * @param reportName the design's name without the extension, which is the name a report in the
 *     catalogue has to carry for this design to be used
 */
public record BirtReportFileUploadData(String fileName, long size, boolean overwritten, String reportName) {}
