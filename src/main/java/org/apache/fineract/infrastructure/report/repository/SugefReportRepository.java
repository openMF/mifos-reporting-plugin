package org.apache.fineract.infrastructure.report.repository;

import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.apache.fineract.infrastructure.report.dto.Registro;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SugefReportRepository {

    private final DataSource dataSource;

    @Autowired
    public SugefReportRepository(@Qualifier("hikariTenantDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * CONSULTA SQL PARA EL REPORTE 44 XML
     */
    public List<Registro> obtenerTransaccionesReporte44(LocalDate fechaInicio, LocalDate fechaFin) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(this.dataSource);

        String sql = """
        WITH EquivalenciasTipoCambio AS (
            SELECT 1 AS moneda_id, 0.0019 AS factor_a_usd UNION ALL
            SELECT 2 AS moneda_id, 1.0000 AS factor_a_usd UNION ALL
            SELECT 3 AS moneda_id, 1.0900 AS factor_a_usd
        ),
        TransaccionesBase AS (
            SELECT t.id AS transaction_id, t.amount AS monto_original,
                   CASE WHEN sa.currency_code = 'CRC' THEN 1 WHEN sa.currency_code = 'USD' THEN 2 WHEN sa.currency_code = 'EUR' THEN 3 ELSE 2 END AS codigo_moneda,
                   t.transaction_date, c.account_no AS cliente_cuenta, c.firstname, c.lastname, c.middlename, c.display_name, c.fullname,
                   CASE WHEN c.client_type_cv_id = 1103 THEN 1 WHEN c.client_type_cv_id = 1105 THEN 2 WHEN c.client_type_cv_id = 1104 THEN 3 ELSE 5 END AS codigo_tipo_identificacion,
                   CASE WHEN t.transaction_type_enum IN (1, 11, 13) THEN 1 ELSE 2 END AS codigo_tipo_movimiento,
                   COALESCE(ri."Pais de Origen", 'CR') AS pais_origen, COALESCE(ri."Pais de Destino", 'CR') AS pais_destino, ri."Motivo del Envio" AS motivo_envio,
                   (t.amount * tc.factor_a_usd) AS monto_en_usd
            FROM m_savings_account_transaction t
            INNER JOIN m_savings_account sa ON t.savings_account_id = sa.id
            INNER JOIN m_client c ON sa.client_id = c.id
            LEFT JOIN public."REMITTANCE_INFORMATION" ri ON t.external_id = ri."Referencia Externa" OR t.ref_no = ri."Referencia Externa"
            LEFT JOIN EquivalenciasTipoCambio tc ON tc.moneda_id = (CASE WHEN sa.currency_code = 'CRC' THEN 1 WHEN sa.currency_code = 'USD' THEN 2 WHEN sa.currency_code = 'EUR' THEN 3 ELSE 2 END)
            WHERE t.transaction_date >= CAST(? AS date) 
              AND t.transaction_date < CAST(? AS date) 
              AND t.is_reversed = false
        ),
        CalculoUmbrales AS (
            SELECT *, SUM(monto_en_usd) OVER (PARTITION BY cliente_cuenta) AS acumulado_mensual_usd FROM TransaccionesBase
        )
        SELECT ROW_NUMBER() OVER (ORDER BY transaction_date) AS id, 'insertar' AS accion, cliente_cuenta AS "NumeroIdentificacion", codigo_tipo_identificacion AS "TipoIdentificacion",
               CASE WHEN codigo_tipo_identificacion IN (1, 3, 5) THEN LEFT(UPPER(COALESCE(firstname, '')), 150) ELSE '' END AS "NombreCliente",
               CASE WHEN codigo_tipo_identificacion IN (1, 3, 5) THEN LEFT(UPPER(COALESCE(lastname, '')), 20) ELSE '' END AS "PrimerApellidoCliente",
               CASE WHEN codigo_tipo_identificacion IN (1, 3, 5) THEN LEFT(UPPER(COALESCE(middlename, '')), 20) ELSE '' END AS "SegundoApellidoCliente",
               CASE WHEN codigo_tipo_identificacion IN (2, 4, 6, 13) THEN LEFT(UPPER(COALESCE(display_name, fullname, '')), 150) ELSE '' END AS "NombreEmpresa",
               2 AS "TipoReporte", CASE WHEN monto_en_usd >= 10000.00 THEN 1 ELSE 2 END AS "TipoOperacion", codigo_tipo_movimiento AS "TipoMovimiento",
               CASE WHEN codigo_tipo_movimiento = 2 THEN 0 ELSE 24 END AS "TipoIngreso", CASE WHEN codigo_tipo_movimiento = 1 THEN 0 ELSE 55 END AS "TipoSalida",
               codigo_moneda AS "TipoMonedaMovimiento", ROUND(CAST(monto_original AS NUMERIC), 2) AS "MontoMovimiento", TO_CHAR(transaction_date, 'DD/MM/YYYY') AS "FechaTransaccion",
               CASE WHEN LENGTH(COALESCE(motivo_envio, 'ADMINISTRACION DE FONDOS OPERATIVOS')) < 10 THEN RPAD(COALESCE(motivo_envio, 'ADMINISTRACION DE FONDOS OPERATIVOS'), 10, '.') ELSE LEFT(COALESCE(motivo_envio, 'ADMINISTRACION DE FONDOS OPERATIVOS'), 250) END AS "MotivoTransaccion",
               'ACTIVIDAD ECONOMICA DECLARADA EN CONTRATO' AS "OrigenRecursos", UPPER(pais_origen) AS "UbicacionCliente", UPPER(pais_origen) AS "PaisOrigenRecursos", UPPER(pais_destino) AS "PaisDestinoRecursos"
        FROM CalculoUmbrales
        WHERE monto_en_usd >= 10000.00 OR acumulado_mensual_usd >= 10000.00
        ORDER BY transaction_date
        """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Registro r = new Registro();
            r.setId(rs.getInt("id"));
            r.setAccion(rs.getString("accion"));
            r.setNumeroIdentificacion(rs.getString("NumeroIdentificacion"));
            r.setTipoIdentificacion(rs.getInt("TipoIdentificacion"));
            r.setNombreCliente(rs.getString("NombreCliente"));
            r.setPrimerApellidoCliente(rs.getString("PrimerApellidoCliente"));
            r.setSegundoApellidoCliente(rs.getString("SegundoApellidoCliente"));
            r.setNombreEmpresa(rs.getString("NombreEmpresa"));
            r.setTipoReporte(rs.getInt("TipoReporte"));
            r.setTipoOperacion(rs.getInt("TipoOperacion"));
            r.setTipoMovimiento(rs.getInt("TipoMovimiento"));
            r.setTipoIngreso(rs.getInt("TipoIngreso"));
            r.setTipoSalida(rs.getInt("TipoSalida"));
            r.setTipoMonedaMovimiento(rs.getInt("TipoMonedaMovimiento"));
            r.setMontoMovimiento(rs.getDouble("MontoMovimiento"));
            r.setFechaTransaccion(rs.getString("FechaTransaccion"));
            r.setMotivoTransaccion(rs.getString("MotivoTransaccion"));
            r.setOrigenRecursos(rs.getString("OrigenRecursos"));
            r.setUbicacionCliente(rs.getString("UbicacionCliente"));
            r.setPaisOrigenRecursos(rs.getString("PaisOrigenRecursos"));
            r.setPaisDestinoRecursos(rs.getString("PaisDestinoRecursos"));
            return r;
        }, java.sql.Date.valueOf(fechaInicio), java.sql.Date.valueOf(fechaFin));
    }

    /**
     * CONSULTA SQL PARA EL REPORTE 45 XML
     */
    public List<Registro> obtenerTransaccionesReporte45(LocalDate fechaInicio, LocalDate fechaFin, Integer officeId) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(this.dataSource);

        String sql = """
            SELECT 
                ROW_NUMBER() OVER (ORDER BY t.transaction_date) AS id,
                'insertar' AS accion,
                c.account_no AS "NumeroIdentificacion",
                CASE WHEN c.client_type_cv_id = 1103 THEN 1 WHEN c.client_type_cv_id = 1105 THEN 2 ELSE 5 END AS "TipoIdentificacion",
                COALESCE(t.ref_no, '') AS "NumeroUnicoTransaccion",
                CASE WHEN c.client_type_cv_id IN (1103, 1104) THEN LEFT(UPPER(COALESCE(c.firstname, '')), 150) ELSE '' END AS "NombreCliente",
                CASE WHEN c.client_type_cv_id IN (1103, 1104) THEN LEFT(UPPER(COALESCE(c.lastname, '')), 20) ELSE '' END AS "PrimerApellidoCliente",
                CASE WHEN c.client_type_cv_id IN (1103, 1104) THEN LEFT(UPPER(COALESCE(c.middlename, '')), 20) ELSE '' END AS "SegundoApellidoCliente",
                CASE WHEN c.client_type_cv_id = 1105 THEN LEFT(UPPER(COALESCE(c.display_name, c.fullname, '')), 150) ELSE '' END AS "NombreEmpresa",
                'CR' AS "PaisOrigenCliente",
                'SAN JOSE' AS "CiudadOrigenCliente",
                '' AS "NombreClienteReceptorRemitente",
                '' AS "PrimerApellidoReceptorRemitente",
                '' AS "SegundoApellidoReceptorRemitente",
                'ONVO COSTA RICA SOCIEDAD ANONIMA' AS "NombreEmpresaClienteReceptorRemitente",
                2 AS "TipoReporte",
                2 AS "TipoOperacion",
                2 AS "TipoMovimiento",
                0 AS "TipoIngreso",
                29 AS "TipoSalida",
                CASE WHEN sa.currency_code = 'CRC' THEN 1 WHEN sa.currency_code = 'USD' THEN 2 ELSE 2 END AS "TipoMonedaMovimiento",
                ROUND(CAST(t.amount AS NUMERIC), 2) AS "MontoMovimiento",
                TO_CHAR(t.transaction_date, 'DD/MM/YYYY') AS "FechaTransaccion",
                'DESEMBOLSO POR VENTAS' AS "MotivoTransaccion",
                'DE LA ACTIVIDAD DECLARADA' AS "OrigenRecursos",
                'CR' AS "PaisDestinoReceptorRemitente",
                'SAN JOSE' AS "CiudadDestinoReceptorRemitente",
                'LAFISE' AS "EntidadExteriorTramitaRemesa",
                'DESEMBOLSOS POR VENTAS' AS "DestinoRecursos"
            FROM m_savings_account_transaction t
            INNER JOIN m_savings_account sa ON t.savings_account_id = sa.id
            INNER JOIN m_client c ON sa.client_id = c.id
            WHERE t.transaction_date >= ?
              AND t.transaction_date < ?
              AND c.office_id = ?
              AND t.is_reversed = false;
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Registro r = new Registro();
            r.setId(rs.getInt("id"));
            r.setAccion(rs.getString("accion"));
            r.setNumeroIdentificacion(rs.getString("NumeroIdentificacion"));
            r.setTipoIdentificacion(rs.getInt("TipoIdentificacion"));
            r.setNumeroUnicoTransaccion(rs.getString("NumeroUnicoTransaccion"));
            r.setNombreCliente(rs.getString("NombreCliente"));
            r.setPrimerApellidoCliente(rs.getString("PrimerApellidoCliente"));
            r.setSegundoApellidoCliente(rs.getString("SegundoApellidoCliente"));
            r.setNombreEmpresa(rs.getString("NombreEmpresa"));
            r.setPaisOrigenCliente(rs.getString("PaisOrigenCliente"));
            r.setCiudadOrigenCliente(rs.getString("CiudadOrigenCliente"));
            r.setNombreClienteReceptorRemitente(rs.getString("NombreClienteReceptorRemitente"));
            r.setPrimerApellidoReceptorRemitente(rs.getString("PrimerApellidoReceptorRemitente"));
            r.setSegundoApellidoReceptorRemitente(rs.getString("SegundoApellidoReceptorRemitente"));
            r.setNombreEmpresaClienteReceptorRemitente(rs.getString("NombreEmpresaClienteReceptorRemitente"));
            r.setTipoReporte(rs.getInt("TipoReporte"));
            r.setTipoOperacion(rs.getInt("TipoOperacion"));
            r.setTipoMovimiento(rs.getInt("TipoMovimiento"));
            r.setTipoIngreso(rs.getInt("TipoIngreso"));
            r.setTipoSalida(rs.getInt("TipoSalida"));
            r.setTipoMonedaMovimiento(rs.getInt("TipoMonedaMovimiento"));
            r.setMontoMovimiento(rs.getDouble("MontoMovimiento"));
            r.setFechaTransaccion(rs.getString("FechaTransaccion"));
            r.setMotivoTransaccion(rs.getString("MotivoTransaccion"));
            r.setOrigenRecursos(rs.getString("OrigenRecursos"));
            r.setPaisDestinoReceptorRemitente(rs.getString("PaisDestinoReceptorRemitente"));
            r.setCiudadDestinoReceptorRemitente(rs.getString("CiudadDestinoReceptorRemitente"));
            r.setEntidadExteriorTramitaRemesa(rs.getString("EntidadExteriorTramitaRemesa"));
            r.setDestinoRecursos(rs.getString("DestinoRecursos"));
            return r;
        }, java.sql.Date.valueOf(fechaInicio), java.sql.Date.valueOf(fechaFin), officeId);
    }
}