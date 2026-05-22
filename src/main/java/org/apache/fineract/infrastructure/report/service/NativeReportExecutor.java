package org.apache.fineract.infrastructure.report.service;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

public interface NativeReportExecutor {

    /**
     * Determina si este ejecutor maneja el reporte solicitado.
     */
    boolean handles(String reportName);

    /**
     * Ejecuta la lógica nativa del reporte y retorna la respuesta HTTP.
     */
    Response execute(String reportName, MultivaluedMap<String, String> queryParams);
}