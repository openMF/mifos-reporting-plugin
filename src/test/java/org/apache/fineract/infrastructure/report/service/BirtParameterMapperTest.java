/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.eclipse.birt.report.engine.api.IGetParameterDefinitionTask;
import org.eclipse.birt.report.engine.api.IParameterDefn;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.engine.api.IRunTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("BirtParameterMapper TDD Tests")
class BirtParameterMapperTest {

    @Mock
    private ReportErrorHandler reportErrorHandler;

    @Mock
    private IReportEngine reportEngine;

    @Mock
    private IRunTask runTask;

    @Mock
    private IReportRunnable reportRunnable;

    @Mock
    private IGetParameterDefinitionTask paramTask;

    @InjectMocks
    private BirtParameterMapper mapper;

    @Test
    @DisplayName("Should skip server managed parameters and map standard required parameters")
    void shouldMapParametersSuccessfully() throws Exception {
        when(runTask.getReportRunnable()).thenReturn(reportRunnable);
        when(reportEngine.createGetParameterDefinitionTask(reportRunnable)).thenReturn(paramTask);

        IParameterDefn managedParam = mock(IParameterDefn.class);
        when(managedParam.getName()).thenReturn("userid");

        IParameterDefn startDateParam = mock(IParameterDefn.class);
        when(startDateParam.getName()).thenReturn("startDate");
        when(startDateParam.isRequired()).thenReturn(true);
        when(startDateParam.getDataType()).thenReturn(IParameterDefn.TYPE_DATE);

        when(paramTask.getParameterDefns(anyBoolean())).thenReturn(List.of(managedParam, startDateParam));

        mapper.applyParameters(runTask, Map.of("startDate", "01 January 2026"));

        verify(runTask).setParameterValue(eq("startDate"), any(java.sql.Date.class));
        verify(paramTask).close();
        // server-managed parameter must never be set from the client map
        verify(runTask, never()).setParameterValue(eq("userid"), any());
    }

    @Test
    @DisplayName("Should collect ALL missing required parameters and throw a single detailed exception")
    void shouldThrowWithAllMissingRequiredParameters() throws Exception {
        when(runTask.getReportRunnable()).thenReturn(reportRunnable);
        when(reportEngine.createGetParameterDefinitionTask(reportRunnable)).thenReturn(paramTask);

        IParameterDefn branchParam = mock(IParameterDefn.class);
        when(branchParam.getName()).thenReturn("branch");
        when(branchParam.isRequired()).thenReturn(true);

        IParameterDefn officeParam = mock(IParameterDefn.class);
        when(officeParam.getName()).thenReturn("officeId");
        when(officeParam.isRequired()).thenReturn(true);

        IParameterDefn optionalParam = mock(IParameterDefn.class);
        when(optionalParam.getName()).thenReturn("optionalFilter");
        when(optionalParam.isRequired()).thenReturn(false);

        when(paramTask.getParameterDefns(anyBoolean())).thenReturn(List.of(branchParam, officeParam, optionalParam));

        when(reportErrorHandler.reportError(eq("error.msg.reporting.missing.parameter"), anyString()))
                .thenAnswer(inv -> new PlatformDataIntegrityException(inv.getArgument(0), inv.getArgument(1)));

        PlatformDataIntegrityException ex = assertThrows(
                PlatformDataIntegrityException.class, () -> mapper.applyParameters(runTask, Collections.emptyMap()));

        String message = ex.getDefaultUserMessage();
        assertTrue(message.contains("branch"), "Should list missing 'branch'");
        assertTrue(message.contains("officeId"), "Should list missing 'officeId'");
        assertFalse(message.contains("optionalFilter"), "Optional parameter must not appear");
    }

    @Test
    @DisplayName("Should allow blank optional parameters without throwing")
    void shouldAllowMissingOptionalParameters() throws Exception {
        when(runTask.getReportRunnable()).thenReturn(reportRunnable);
        when(reportEngine.createGetParameterDefinitionTask(reportRunnable)).thenReturn(paramTask);

        IParameterDefn optionalParam = mock(IParameterDefn.class);
        when(optionalParam.getName()).thenReturn("optionalFilter");
        when(optionalParam.isRequired()).thenReturn(false);

        when(paramTask.getParameterDefns(anyBoolean())).thenReturn(List.of(optionalParam));

        // No exception expected
        mapper.applyParameters(runTask, Collections.emptyMap());

        verify(runTask, never()).setParameterValue(anyString(), any());
        verify(paramTask).close();
    }

    @Test
    @DisplayName("Should throw when a single required parameter is missing")
    void shouldThrowExceptionWhenSingleRequiredParameterMissing() throws Exception {
        when(runTask.getReportRunnable()).thenReturn(reportRunnable);
        when(reportEngine.createGetParameterDefinitionTask(reportRunnable)).thenReturn(paramTask);

        IParameterDefn missingParam = mock(IParameterDefn.class);
        when(missingParam.getName()).thenReturn("branch");
        when(missingParam.isRequired()).thenReturn(true);

        when(paramTask.getParameterDefns(anyBoolean())).thenReturn(List.of(missingParam));

        when(reportErrorHandler.reportError(eq("error.msg.reporting.missing.parameter"), anyString()))
                .thenAnswer(inv -> new PlatformDataIntegrityException(inv.getArgument(0), inv.getArgument(1)));

        PlatformDataIntegrityException ex = assertThrows(
                PlatformDataIntegrityException.class, () -> mapper.applyParameters(runTask, Collections.emptyMap()));

        assertTrue(ex.getDefaultUserMessage().contains("branch"));
        // Important: the specific missing-parameter exception must NOT be re-wrapped
        // into a generic "Failed to process report parameters"
    }
}
