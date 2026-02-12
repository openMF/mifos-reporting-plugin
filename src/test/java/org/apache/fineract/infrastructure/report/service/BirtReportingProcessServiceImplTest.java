/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.infrastructure.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import javax.sql.DataSource;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BirtReportingProcessServiceImplTest {

  @Mock private IReportEngine reportEngine;

  @Mock private DataSource dataSource;

  @Mock private Connection connection;

  @Mock private IReportRunnable reportRunnable;

  @Mock private IRunAndRenderTask runAndRenderTask;

  private BirtReportingProcessServiceImpl service;

  @BeforeEach
  void setUp() throws Exception {
    // Initialize the service with mocked dependencies
    service = new BirtReportingProcessServiceImpl(reportEngine, dataSource);
  }

  @Test
  void testProcessRequestPdf() throws Exception {
    // Arrange
    String reportName = "TestReport";
    MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<>();
    queryParams.add("output-type", "PDF");
    queryParams.add("R_startDate", "2023-01-01");

    // Mock BIRT Engine behavior
    when(reportEngine.openReportDesign(anyString())).thenReturn(reportRunnable);
    when(reportEngine.createRunAndRenderTask(reportRunnable)).thenReturn(runAndRenderTask);

    // Mock DataSource behavior
    when(dataSource.getConnection()).thenReturn(connection);

    // Mock the Task Context map (needed for injection)
    when(runAndRenderTask.getAppContext()).thenReturn(new java.util.HashMap<>());

    // Act
    Response response = service.processRequest(reportName, queryParams);

    // Assert
    assertNotNull(response);
    assertEquals(200, response.getStatus());
    assertEquals("application/pdf", response.getMediaType().toString());

    // Verify Interactions
    verify(reportEngine).openReportDesign(anyString());
    verify(runAndRenderTask).setRenderOption(any());
    verify(runAndRenderTask).run();
    verify(runAndRenderTask).close();
    verify(dataSource).getConnection(); // Ensure DB connection was requested
  }
}
