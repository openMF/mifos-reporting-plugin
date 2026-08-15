/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.exporter;

/**
 * Domain-specific exception for failures encountered during BIRT XML serialization or file output.
 */
public class BirtXmlExportException extends RuntimeException {

    public BirtXmlExportException(String message) {
        super(message);
    }

    public BirtXmlExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
