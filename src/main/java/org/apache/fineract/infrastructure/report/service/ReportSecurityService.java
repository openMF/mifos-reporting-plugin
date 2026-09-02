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
     * Apache Fineract permission required to install a report design. It is the same grant that
     * authorises adding a report to the catalogue, so no new permission is introduced: a user who
     * may register a report already decides what a report is, and one who may not has no route to
     * the reports directory either.
     */
    public static final String CREATE_REPORT_PERMISSION = "CREATE_REPORT";

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

    /**
     * Verifies that the currently authenticated Apache Fineract user may install a report design.
     *
     * <p>Hiding the button in the web app is not a check; this is. It runs before the uploaded file
     * is read, validated or written.
     *
     * <p>The user comes from {@link PlatformSecurityContext} and the grant is checked by Apache
     * Fineract's own {@link AppUser#validateHasPermissionTo(String)}, so this resolves roles the way
     * the rest of the platform does. That also settles the blanket grants correctly: {@code
     * ALL_FUNCTIONS} passes, while {@code ALL_FUNCTIONS_READ} does not, because installing a design
     * is a write.
     *
     * @throws NoAuthorizationException when the user does not have {@code CREATE_REPORT}
     */
    public void checkCreateReportPermission() {

        securityContext.authenticatedUser().validateHasPermissionTo(CREATE_REPORT_PERMISSION);
    }
}
