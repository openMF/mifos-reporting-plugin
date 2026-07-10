/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
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

  @Mock private ReportErrorHandler reportErrorHandler;
  @Mock private IReportEngine reportEngine;
  @Mock private IRunTask runTask;
  @Mock private IReportRunnable reportRunnable;
  @Mock private IGetParameterDefinitionTask paramTask;
  @Mock private IParameterDefn parameterDefn;

  @InjectMocks private BirtParameterMapper mapper;

  // Senior Dev Note: Removed the bloated @BeforeEach and lenient() mocks.
  // We only mock what we actually use in the specific tests.

  @Test
  @DisplayName("Should skip server managed parameters and map standard parameters")
  void shouldMapParametersSuccessfully() throws Exception {
    when(runTask.getReportRunnable()).thenReturn(reportRunnable);
    when(reportEngine.createGetParameterDefinitionTask(reportRunnable)).thenReturn(paramTask);

    IParameterDefn managedParam = mock(IParameterDefn.class);
    when(managedParam.getName()).thenReturn("userid");

    when(parameterDefn.getName()).thenReturn("R_startDate");
    when(parameterDefn.getDataType()).thenReturn(IParameterDefn.TYPE_DATE);

    when(paramTask.getParameterDefns(anyBoolean()))
        .thenReturn(java.util.List.of(managedParam, parameterDefn));

    mapper.applyParameters(runTask, Map.of("R_startDate", "01 January 2026"));

    verify(runTask).setParameterValue(eq("R_startDate"), any(java.sql.Date.class));
    verify(paramTask).close();
  }

  @Test
  @DisplayName("Should throw exception if required parameter is missing")
  void shouldThrowExceptionWhenParameterMissing() throws Exception {
    when(runTask.getReportRunnable()).thenReturn(reportRunnable);
    when(reportEngine.createGetParameterDefinitionTask(reportRunnable)).thenReturn(paramTask);
    when(parameterDefn.getName()).thenReturn("R_missingParam");
    when(paramTask.getParameterDefns(anyBoolean())).thenReturn(java.util.List.of(parameterDefn));

    // 1. Mock the initial 2-argument error when the parameter is missing
    when(reportErrorHandler.reportError(anyString(), anyString()))
        .thenReturn(
            new PlatformDataIntegrityException(
                "error.msg.reporting.missing.parameter", "Missing Parameter"));

    // 2. Mock the 3-argument error when the catch block intercepts it and re-wraps it!
    when(reportErrorHandler.reportError(anyString(), anyString(), any()))
        .thenReturn(
            new PlatformDataIntegrityException("error.msg.reporting.error", "Wrapped Exception"));

    assertThrows(
        PlatformDataIntegrityException.class,
        () -> mapper.applyParameters(runTask, Collections.emptyMap()));
  }
}
