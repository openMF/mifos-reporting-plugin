package org.apache.fineract.infrastructure.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class BirtReportingProcessServiceImplTest {

  // We create a fake database just for the test
  @Mock private DataSource dataSource;

  // Real code
  @InjectMocks private BirtReportingProcessServiceImpl birtService;

  // Test 1: Check if the service starts correctly
  @Test
  public void testServiceIsInstantiatedCorrectly() {
    assertNotNull(birtService, "The BIRT service should exist and not be empty");
  }

  // Test 2: Check if the service has the 5 correct formats (PDF, HTML, CSV, XLS, XLSX)
  @Test
  public void testGetAvailableExportTargets() {
    var targets = birtService.getAvailableExportTargets();

    assertNotNull(targets, "The format list must not be empty");
    assertEquals(5, targets.size(), "It must have exactly 5 formats (PDF, HTML, CSV, XLS, XLSX)");
  }

  // Test 3: Check what happens if a user asks for a report file that does not exist
  @Test
  public void testProcessRequest_WithNonExistentReport_Returns404() {
    // We create a fake empty web request
    @SuppressWarnings("unchecked")
    jakarta.ws.rs.core.MultivaluedMap<String, String> queryParams =
        org.mockito.Mockito.mock(jakarta.ws.rs.core.MultivaluedMap.class);

    // Run the code asking for a fake report name
    jakarta.ws.rs.core.Response response =
        birtService.processRequest("fake_report_12345", queryParams);

    // The system must reply with a 404 Error (File Not Found)
    assertNotNull(response, "The answer should not be empty");
    assertEquals(404, response.getStatus(), "It must return a 404 error when the file is missing");
  }

  // Test 4: Check if the code cleans the web link parameters correctly
  @Test
  public void testGetReportParams_RemovesRPrefixAndIgnoresNulls() {
    // We simulate a web link exactly how Fineract sends it, using Lists
    jakarta.ws.rs.core.MultivaluedHashMap<String, String> queryParams =
        new jakarta.ws.rs.core.MultivaluedHashMap<>();

    queryParams.put("R_userId", java.util.Arrays.asList("1"));
    queryParams.put("R_officeId", java.util.Arrays.asList((String) null));
    queryParams.put("exportType", java.util.Arrays.asList("pdf"));

    // Run the code that cleans the parameters
    Map<String, String> reportParams = birtService.getReportParams(queryParams);

    // Check the results
    assertEquals(1, reportParams.size(), "It should only save 1 good parameter");
    assertEquals("1", reportParams.get("userId"), "It should delete the 'R_' part from the word");
    org.junit.jupiter.api.Assertions.assertNull(
        reportParams.get("exportType"), "It should ignore words without 'R_'");
  }

  // Test 5: Check if the system crashes when the web link is completely empty
  @Test
  public void testProcessRequest_WithNullParameters_ShouldNotCrash() {
    // We create a fake web request with empty information
    @SuppressWarnings("unchecked")
    jakarta.ws.rs.core.MultivaluedMap<String, String> queryParams =
        org.mockito.Mockito.mock(jakarta.ws.rs.core.MultivaluedMap.class);

    org.mockito.Mockito.lenient().when(queryParams.getFirst("exportType")).thenReturn(null);
    org.mockito.Mockito.lenient().when(queryParams.getFirst("locale")).thenReturn(null);

    // Make sure the code does not break or crash the server
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> {
          jakarta.ws.rs.core.Response response =
              birtService.processRequest("usersList", queryParams);
          assertNotNull(response);
        },
        "The code must survive and not crash when the words are empty");
  }
}
