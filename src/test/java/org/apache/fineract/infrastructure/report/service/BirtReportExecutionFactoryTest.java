/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.eclipse.birt.report.model.api.core.IDesignElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("BirtReportExecutionFactory Tests")
class BirtReportExecutionFactoryTest {

  @Mock private IReportEngine reportEngine;
  @Mock private BirtReportLoader reportLoader;
  @Mock private ReportErrorHandler reportErrorHandler;

  @InjectMocks private BirtReportExecutionFactory executionFactory;

  @Test
  @DisplayName("Should create execution runnable with independent design handle")
  void shouldCreateExecutionRunnableWithIndependentDesignHandle() throws Exception {
    IReportRunnable templateRunnable = mock(IReportRunnable.class);
    IReportRunnable executionRunnable = mock(IReportRunnable.class);
    ReportDesignHandle templateHandle = mock(ReportDesignHandle.class);
    ReportDesignHandle executionHandle = mock(ReportDesignHandle.class);
    IDesignElement copiedElement = mock(IDesignElement.class);

    when(reportLoader.loadReport("sample", Locale.ENGLISH)).thenReturn(templateRunnable);
    when(templateRunnable.getDesignHandle()).thenReturn(templateHandle);
    when(templateRunnable.getReportName()).thenReturn("sample.rptdesign");
    when(templateHandle.copy()).thenReturn(copiedElement);
    when(copiedElement.getHandle(null)).thenReturn(executionHandle);
    when(reportEngine.openReportDesign(executionHandle)).thenReturn(executionRunnable);
    when(executionRunnable.getDesignHandle()).thenReturn(executionHandle);

    IReportRunnable result = executionFactory.createExecutionRunnable("sample", Locale.ENGLISH);

    assertSame(executionRunnable, result);
    assertNotSame(templateHandle, result.getDesignHandle());
    verify(reportEngine).openReportDesign(executionHandle);
  }

  @Test
  @DisplayName("Should not mutate cached template when execution copy is modified")
  void shouldNotMutateCachedTemplateWhenExecutionCopyIsModified() throws Exception {
    IReportRunnable templateRunnable = mock(IReportRunnable.class);
    IReportRunnable executionRunnable = mock(IReportRunnable.class);
    ReportDesignHandle templateHandle = mock(ReportDesignHandle.class);
    ReportDesignHandle executionHandle = mock(ReportDesignHandle.class);
    IDesignElement copiedElement = mock(IDesignElement.class);

    when(reportLoader.loadReport("sample", Locale.ENGLISH)).thenReturn(templateRunnable);
    when(templateRunnable.getDesignHandle()).thenReturn(templateHandle);
    when(templateRunnable.getReportName()).thenReturn("sample.rptdesign");
    when(templateHandle.copy()).thenReturn(copiedElement);
    when(copiedElement.getHandle(null)).thenReturn(executionHandle);
    when(reportEngine.openReportDesign(executionHandle)).thenReturn(executionRunnable);

    executionFactory.createExecutionRunnable("sample", Locale.ENGLISH);

    verify(executionHandle).setFileName("sample.rptdesign");
    verify(templateHandle, never()).setFileName("sample.rptdesign");
    verify(reportEngine, never()).openReportDesign(templateHandle);
  }

  @Test
  @DisplayName("Should create independent execution copies")
  void shouldCreateIndependentExecutionCopies() throws Exception {
    IReportRunnable templateRunnable = mock(IReportRunnable.class);
    IReportRunnable firstExecutionRunnable = mock(IReportRunnable.class);
    IReportRunnable secondExecutionRunnable = mock(IReportRunnable.class);
    ReportDesignHandle templateHandle = mock(ReportDesignHandle.class);
    ReportDesignHandle firstExecutionHandle = mock(ReportDesignHandle.class);
    ReportDesignHandle secondExecutionHandle = mock(ReportDesignHandle.class);
    IDesignElement firstCopiedElement = mock(IDesignElement.class);
    IDesignElement secondCopiedElement = mock(IDesignElement.class);

    when(reportLoader.loadReport("sample", Locale.ENGLISH)).thenReturn(templateRunnable);
    when(templateRunnable.getDesignHandle()).thenReturn(templateHandle);
    when(templateRunnable.getReportName()).thenReturn("sample.rptdesign");
    when(templateHandle.copy()).thenReturn(firstCopiedElement, secondCopiedElement);
    when(firstCopiedElement.getHandle(null)).thenReturn(firstExecutionHandle);
    when(secondCopiedElement.getHandle(null)).thenReturn(secondExecutionHandle);
    when(reportEngine.openReportDesign(firstExecutionHandle)).thenReturn(firstExecutionRunnable);
    when(reportEngine.openReportDesign(secondExecutionHandle)).thenReturn(secondExecutionRunnable);
    when(firstExecutionRunnable.getDesignHandle()).thenReturn(firstExecutionHandle);
    when(secondExecutionRunnable.getDesignHandle()).thenReturn(secondExecutionHandle);

    IReportRunnable first = executionFactory.createExecutionRunnable("sample", Locale.ENGLISH);
    IReportRunnable second = executionFactory.createExecutionRunnable("sample", Locale.ENGLISH);

    assertNotSame(first, second);
    assertNotSame(first.getDesignHandle(), second.getDesignHandle());
    assertNotSame(templateHandle, first.getDesignHandle());
    assertNotSame(templateHandle, second.getDesignHandle());
  }
}
