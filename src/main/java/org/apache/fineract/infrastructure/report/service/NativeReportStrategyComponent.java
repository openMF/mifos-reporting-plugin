package org.apache.fineract.infrastructure.report.service;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.report.dto.ArchivoSICVECA;
import org.apache.fineract.infrastructure.report.dto.Encabezado;
import org.apache.fineract.infrastructure.report.dto.Registro;
import org.apache.fineract.infrastructure.report.repository.SugefReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class NativeReportStrategyComponent {

    private static final Logger logger = LoggerFactory.getLogger(NativeReportStrategyComponent.class);
    private final SugefReportRepository reportRepository;
    private final XmlMapper xmlMapper;

    @Autowired
    public NativeReportStrategyComponent(SugefReportRepository reportRepository) {
        this.reportRepository = reportRepository;
        this.xmlMapper = new XmlMapper();
        this.xmlMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public boolean isNativeReport(String reportName) {
        return "Reporte44Xml".equalsIgnoreCase(reportName)
                || "Reporte45Xml".equalsIgnoreCase(reportName);
    }

    public Response processNativeRequest(String reportName, Map<String, String> queryParams) {

        if (reportName.equalsIgnoreCase("Reporte44Xml")) {
            logger.debug("PROCESANDO REPORTE 44 NATIVO POR COMPONENTE (XML)");
            try {
                String startDateParam = queryParams.get("startDate");
                String endDateParam = queryParams.get("endDate");

                LocalDate startDate;
                LocalDate endDate;

                if (StringUtils.isBlank(startDateParam) || StringUtils.isBlank(endDateParam)) {
                    startDate = LocalDate.now().minusMonths(1).withDayOfMonth(1);
                    endDate = LocalDate.now().withDayOfMonth(1);
                } else {
                    startDate = LocalDate.parse(startDateParam);
                    endDate = LocalDate.parse(endDateParam);
                }

                ArchivoSICVECA archivo = new ArchivoSICVECA();
                Encabezado encabezado = new Encabezado();
                encabezado.setClaseDato(44);
                encabezado.setVersionClaseDato("1.0");
                encabezado.setArchivo(4401);
                encabezado.setVersionArchivo("1.0");
                encabezado.setPeriodo(startDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
                encabezado.setIdEntidad("3102934185");
                encabezado.setTipoCarga(1);
                encabezado.setTipoMoneda(1);
                archivo.setEncabezado(encabezado);

                List<Registro> registros = this.reportRepository.obtenerTransaccionesReporte44(startDate, endDate);
                archivo.setDatos(registros);

                String xmlString = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" + this.xmlMapper.writeValueAsString(archivo);
                return Response.ok().entity(xmlString.getBytes(StandardCharsets.UTF_8)).type("application/xml; charset=UTF-8")
                        .header("Content-Disposition", "attachment;filename=" + reportName + ".xml").build();
            } catch (Exception e) {
                throw new PlatformDataIntegrityException("error.msg.reporting.error", "Fallo XML 44: " + e.getMessage());
            }
        }

        else if (reportName.equalsIgnoreCase("Reporte45Xml")) {
            logger.debug("PROCESANDO REPORTE 45 NATIVO POR COMPONENTE (XML)");
            try {
                String startDateParam = queryParams.get("startDate");
                String endDateParam = queryParams.get("endDate");
                String officeParam = queryParams.get("officeId");

                if (StringUtils.isBlank(officeParam)) {
                    throw new PlatformDataIntegrityException("error.msg.parameter.required", "El parametro officeId es obligatorio para el Reporte 45");
                }
                Integer officeId = Integer.parseInt(officeParam);

                LocalDate startDate;
                LocalDate endDate;

                if (StringUtils.isBlank(startDateParam) || StringUtils.isBlank(endDateParam)) {
                    startDate = LocalDate.now().minusMonths(1).withDayOfMonth(1);
                    endDate = LocalDate.now().withDayOfMonth(1);
                } else {
                    startDate = LocalDate.parse(startDateParam);
                    endDate = LocalDate.parse(endDateParam);
                }

                ArchivoSICVECA archivo = new ArchivoSICVECA();
                Encabezado encabezado = new Encabezado();
                encabezado.setClaseDato(45);
                encabezado.setVersionClaseDato("1.0");
                encabezado.setArchivo(4501);
                encabezado.setVersionArchivo("1.0");
                encabezado.setPeriodo(startDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
                encabezado.setIdEntidad("3102934185");
                encabezado.setTipoCarga(1);
                encabezado.setTipoMoneda(1);
                archivo.setEncabezado(encabezado);

                List<Registro> registros = this.reportRepository.obtenerTransaccionesReporte45(startDate, endDate, officeId);
                archivo.setDatos(registros);

                String xmlString = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" + this.xmlMapper.writeValueAsString(archivo);
                return Response.ok().entity(xmlString.getBytes(StandardCharsets.UTF_8)).type("application/xml; charset=UTF-8")
                        .header("Content-Disposition", "attachment;filename=" + reportName + ".xml").build();
            } catch (Exception e) {
                throw new PlatformDataIntegrityException("error.msg.reporting.error", "Fallo XML 45: " + e.getMessage());
            }
        }

        throw new PlatformDataIntegrityException("error.msg.invalid.report", "No se reconoce el reporte nativo: " + reportName);
    }
}