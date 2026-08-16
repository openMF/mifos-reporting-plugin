/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class ReportSecurityService {

    /**
     * Apache Fineract permission required to execute reports.
     */
    private static final String ALL_FUNCTIONS = "ALL_FUNCTIONS";

    public static final String READ_REPORT_PERMISSION = "READ_REPORT";

    private static final String ERROR_CODE = "error.msg.reporting.permission.denied";

    /**
     * Verifies that the currently authenticated Apache Fineract user is authorized to read reports.
     *
     * <p>The check is deliberately performed before report loading, BIRT execution, datasource
     * access, and SQL execution.
     *
     * @throws PlatformDataIntegrityException when the authenticated user does not have
     *         {@code READ_REPORT}
     */
    public void checkReadReportPermission() {

        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (!isAuthenticated(authentication)) {
            throw new PlatformDataIntegrityException(
                    ERROR_CODE, "The authenticated user is not authorized to execute reports.");
        }

        if (!hasReadReportPermission(authentication)) {
            throw new PlatformDataIntegrityException(
                    ERROR_CODE, "The authenticated user does not have the READ_REPORT permission.");
        }
    }

    private boolean isAuthenticated(Authentication authentication) {

        return authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() != null;
    }

    private boolean hasReadReportPermission(Authentication authentication) {
        final var authorities = authentication.getAuthorities();
        if (authorities == null) {
            return false;
        }
        return authorities.stream().anyMatch(authority -> {
            final String granted = authority.getAuthority();
            return READ_REPORT_PERMISSION.equals(granted) || ALL_FUNCTIONS.equals(granted);
        });
    }
}
