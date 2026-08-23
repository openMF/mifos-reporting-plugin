/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportSecurityService Tests")
class ReportSecurityServiceTest {

    private static final String REPORT_NAME = "Active_Loans_Details";

    @Mock
    private PlatformSecurityContext securityContext;

    @Mock
    private AppUser authenticatedUser;

    @InjectMocks
    private ReportSecurityService reportSecurityService;

    @BeforeEach
    void setUp() {
        when(securityContext.authenticatedUser()).thenReturn(authenticatedUser);
    }

    @Test
    @DisplayName("Should allow execution when the authenticated user is granted the report")
    void shouldAllowExecutionWhenUserIsGrantedTheReport() {
        when(authenticatedUser.hasNotPermissionForReport(REPORT_NAME)).thenReturn(false);

        assertThatCode(() -> reportSecurityService.checkReportExecutionPermission(REPORT_NAME))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject execution when the authenticated user is not granted the report")
    void shouldRejectExecutionWhenUserIsNotGrantedTheReport() {
        when(authenticatedUser.hasNotPermissionForReport(REPORT_NAME)).thenReturn(true);

        assertThatThrownBy(() -> reportSecurityService.checkReportExecutionPermission(REPORT_NAME))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessageContaining(REPORT_NAME);
    }

    /**
     * The grant is per report, not a blanket "may run reports": a user granted one report must not
     * reach another by name.
     */
    @Test
    @DisplayName("Should check the grant for the report being executed, not a generic one")
    void shouldCheckTheGrantForTheRequestedReport() {
        when(authenticatedUser.hasNotPermissionForReport("Other_Report")).thenReturn(true);

        assertThatThrownBy(() -> reportSecurityService.checkReportExecutionPermission("Other_Report"))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessageContaining("Other_Report");
    }
}
