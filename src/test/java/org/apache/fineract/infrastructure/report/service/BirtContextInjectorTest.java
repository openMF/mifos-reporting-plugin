/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.eclipse.birt.report.engine.api.IRunTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("BirtContextInjector TDD Tests")
class BirtContextInjectorTest {

    @Mock
    private PlatformSecurityContext securityContext;

    @Mock
    private ReportErrorHandler reportErrorHandler;

    @Mock
    private IRunTask runTask;

    @InjectMocks
    private BirtContextInjector injector;

    @Test
    @DisplayName("Should successfully inject user hierarchy and userid into task")
    void shouldInjectContextSuccessfully() {
        AppUser mockUser = mock(AppUser.class, RETURNS_DEEP_STUBS);
        FineractPlatformTenant mockTenant = mock(FineractPlatformTenant.class);

        when(securityContext.authenticatedUser()).thenReturn(mockUser);
        when(mockUser.getId()).thenReturn(1L);
        when(mockUser.getOffice().getHierarchy()).thenReturn(".1.");
        when(mockTenant.getName()).thenReturn("default");

        try (MockedStatic<ThreadLocalContextUtil> mockedThreadLocal = mockStatic(ThreadLocalContextUtil.class)) {
            mockedThreadLocal.when(ThreadLocalContextUtil::getTenant).thenReturn(mockTenant);

            injector.injectContextParameters(runTask);

            verify(runTask).setParameterValue("userhierarchy", ".1.");
            verify(runTask).setParameterValue("userid", 1L);
        }
    }
}
