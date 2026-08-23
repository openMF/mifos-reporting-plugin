/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReportSecurityService {

    private final PlatformSecurityContext securityContext;

    /**
     * Verifies that the authenticated Apache Fineract user is granted the report that is about to be
     * executed.
     *
     * <p>The user is taken from {@link PlatformSecurityContext}, never from the request, and the
     * grants are the ones Apache Fineract itself requires to run a report ({@code READ_<reportName>},
     * {@code REPORTING_SUPER_USER}, {@code ALL_FUNCTIONS} or {@code ALL_FUNCTIONS_READ}).
     *
     * <p>The check is deliberately performed inside {@code processRequest}, before report loading,
     * BIRT execution and SQL execution, because that is the only point every caller passes through:
     * the {@code /runreports} resource checks the grant itself, but the report mailing job does not.
     *
     * @throws NoAuthorizationException when the authenticated user is not granted the report
     */
    public void checkReportExecutionPermission(final String reportName) {

        final AppUser currentUser = securityContext.authenticatedUser();

        if (currentUser.hasNotPermissionForReport(reportName)) {
            throw new NoAuthorizationException("Not authorised to run report: " + reportName);
        }
    }
}
