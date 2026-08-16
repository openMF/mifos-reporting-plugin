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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportSecurityService Tests")
class ReportSecurityServiceTest {

    private ReportSecurityService reportSecurityService;

    @BeforeEach
    void setUp() {
        reportSecurityService = new ReportSecurityService();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Should allow report execution when user has READ_REPORT permission")
    void shouldAllowReportExecutionWhenUserHasReadReportPermission() {

        Authentication authentication = authenticatedUser(
                "report-user", List.of(new SimpleGrantedAuthority(ReportSecurityService.READ_REPORT_PERMISSION)));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThatCode(() -> reportSecurityService.checkReadReportPermission()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject report execution when user does not have READ_REPORT permission")
    void shouldRejectReportExecutionWhenUserDoesNotHaveReadReportPermission() {

        Authentication authentication = authenticatedUser(
                "normal-user",
                List.of(new SimpleGrantedAuthority("READ_CLIENT"), new SimpleGrantedAuthority("READ_LOAN")));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThatThrownBy(() -> reportSecurityService.checkReadReportPermission())
                .isInstanceOf(PlatformDataIntegrityException.class)
                .hasMessageContaining("READ_REPORT");
    }

    @Test
    @DisplayName("Should reject report execution when authentication is null")
    void shouldRejectReportExecutionWhenAuthenticationIsNull() {

        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> reportSecurityService.checkReadReportPermission())
                .isInstanceOf(PlatformDataIntegrityException.class)
                .hasMessageContaining("not authorized");
    }

    @Test
    @DisplayName("Should reject report execution when user is not authenticated")
    void shouldRejectReportExecutionWhenUserIsNotAuthenticated() {

        Authentication authentication = mock(Authentication.class);

        when(authentication.isAuthenticated()).thenReturn(false);

        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThatThrownBy(() -> reportSecurityService.checkReadReportPermission())
                .isInstanceOf(PlatformDataIntegrityException.class)
                .hasMessageContaining("not authorized");
    }

    @Test
    @DisplayName("Should reject report execution when user has no authorities")
    void shouldRejectReportExecutionWhenUserHasNoAuthorities() {

        Authentication authentication = authenticatedUser("user-without-authorities", List.of());

        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThatThrownBy(() -> reportSecurityService.checkReadReportPermission())
                .isInstanceOf(PlatformDataIntegrityException.class)
                .hasMessageContaining("READ_REPORT");
    }

    @Test
    @DisplayName("Should allow report execution when READ_REPORT exists among multiple permissions")
    void shouldAllowReportExecutionWhenReadReportExistsAmongMultiplePermissions() {

        Authentication authentication = authenticatedUser(
                "report-user",
                List.of(
                        new SimpleGrantedAuthority("READ_CLIENT"),
                        new SimpleGrantedAuthority("READ_LOAN"),
                        new SimpleGrantedAuthority("READ_SAVINGS"),
                        new SimpleGrantedAuthority(ReportSecurityService.READ_REPORT_PERMISSION),
                        new SimpleGrantedAuthority("READ_ACCOUNT")));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThatCode(() -> reportSecurityService.checkReadReportPermission()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject similar but incorrect report permissions")
    void shouldRejectSimilarButIncorrectReportPermissions() {

        Authentication authentication = authenticatedUser(
                "user",
                List.of(
                        new SimpleGrantedAuthority("READ_REPORTS"),
                        new SimpleGrantedAuthority("REPORT_READ"),
                        new SimpleGrantedAuthority("WRITE_REPORT")));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThatThrownBy(() -> reportSecurityService.checkReadReportPermission())
                .isInstanceOf(PlatformDataIntegrityException.class)
                .hasMessageContaining("READ_REPORT");
    }

    @Test
    @DisplayName("Should require exact READ_REPORT permission")
    void shouldRequireExactReadReportPermission() {

        Authentication authentication = authenticatedUser(
                "user",
                List.of(
                        new SimpleGrantedAuthority("read_report"),
                        new SimpleGrantedAuthority("READ_REPORTS"),
                        new SimpleGrantedAuthority("READ_REPORT ")));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThatThrownBy(() -> reportSecurityService.checkReadReportPermission())
                .isInstanceOf(PlatformDataIntegrityException.class)
                .hasMessageContaining("READ_REPORT");
    }

    @Test
    @DisplayName("Should allow authenticated user with only READ_REPORT permission")
    void shouldAllowAuthenticatedUserWithOnlyReadReportPermission() {

        Authentication authentication = authenticatedUser(
                "report-user", List.of(new SimpleGrantedAuthority(ReportSecurityService.READ_REPORT_PERMISSION)));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThatCode(() -> reportSecurityService.checkReadReportPermission()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should not depend on username when checking READ_REPORT")
    void shouldNotDependOnUsernameWhenCheckingReadReport() {

        Authentication authentication = authenticatedUser(
                "any-user", List.of(new SimpleGrantedAuthority(ReportSecurityService.READ_REPORT_PERMISSION)));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThatCode(() -> reportSecurityService.checkReadReportPermission()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject authentication with null authorities")
    void shouldRejectAuthenticationWithNullAuthorities() {

        Authentication authentication = mock(Authentication.class);

        when(authentication.isAuthenticated()).thenReturn(true);

        when(authentication.getPrincipal()).thenReturn("report-user");

        when(authentication.getAuthorities()).thenReturn(null);

        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThatThrownBy(() -> reportSecurityService.checkReadReportPermission())
                .isInstanceOf(PlatformDataIntegrityException.class);
    }

    private Authentication authenticatedUser(String username, List<SimpleGrantedAuthority> authorities) {

        return new UsernamePasswordAuthenticationToken(username, "password", authorities);
    }
}
