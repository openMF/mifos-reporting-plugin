/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.report.migration.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.fineract.infrastructure.report.migration.model.PentahoReportModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

@ExtendWith(MockitoExtension.class)
class ReportModelMapperTest {

  @Mock private BirtDomBuilder domBuilder;

  @InjectMocks private ReportModelMapper reportModelMapper;

  @Test
  void givenReportModel_whenMapped_thenTransformsDomToXmlString() throws Exception {
    // Arrange
    Document dummyDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
    Element root = dummyDoc.createElement("report");
    root.setAttribute("testAttribute", "success");
    dummyDoc.appendChild(root);

    when(domBuilder.buildReportDocument(any(PentahoReportModel.class))).thenReturn(dummyDoc);

    PentahoReportModel dummyModel =
        new PentahoReportModel("Dummy", List.of(), List.of(), List.of());

    // Act
    String resultXml = reportModelMapper.mapToBirtTemplate(dummyModel);

    // Assert

    assertThat(resultXml).contains("<?xml version=\"1.0\"");
    assertThat(resultXml).contains("encoding=\"UTF-8\"");
    assertThat(resultXml).contains("<report testAttribute=\"success\"/>");
  }
}
