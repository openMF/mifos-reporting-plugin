package org.apache.fineract.infrastructure.report.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Registro {

    // Atributos obligatorios en la etiqueta raíz <Registro id="X" accion="Y">
    @JacksonXmlProperty(isAttribute = true)
    private int id;

    @JacksonXmlProperty(isAttribute = true)
    private String accion;

    // --- CAMPOS COMPARTIDOS (REPORTE 44 Y REPORTE 45) ---
    @JacksonXmlProperty(localName = "NumeroIdentificacion")
    private String numeroIdentificacion;

    @JacksonXmlProperty(localName = "TipoIdentificacion")
    private int tipoIdentificacion;

    @JacksonXmlProperty(localName = "NombreCliente")
    private String nombreCliente;

    @JacksonXmlProperty(localName = "PrimerApellidoCliente")
    private String primerApellidoCliente;

    @JacksonXmlProperty(localName = "SegundoApellidoCliente")
    private String segundoApellidoCliente;

    @JacksonXmlProperty(localName = "NombreEmpresa")
    private String nombreEmpresa;

    @JacksonXmlProperty(localName = "TipoReporte")
    private int tipoReporte;

    @JacksonXmlProperty(localName = "TipoOperacion")
    private int tipoOperacion;

    @JacksonXmlProperty(localName = "TipoMovimiento")
    private int tipoMovimiento;

    @JacksonXmlProperty(localName = "TipoIngreso")
    private int tipoIngreso;

    @JacksonXmlProperty(localName = "TipoSalida")
    private int tipoSalida;

    @JacksonXmlProperty(localName = "TipoMonedaMovimiento")
    private int tipoMonedaMovimiento;

    @JacksonXmlProperty(localName = "MontoMovimiento")
    private double montoMovimiento;

    @JacksonXmlProperty(localName = "FechaTransaccion")
    private String fechaTransaccion;

    @JacksonXmlProperty(localName = "MotivoTransaccion")
    private String motivoTransaccion;

    @JacksonXmlProperty(localName = "OrigenRecursos")
    private String origenRecursos;

    // --- CAMPOS EXCLUSIVOS DEL REPORTE 44 ---
    @JacksonXmlProperty(localName = "UbicacionCliente")
    private String ubicacionCliente;

    @JacksonXmlProperty(localName = "PaisOrigenRecursos")
    private String paisOrigenRecursos;

    @JacksonXmlProperty(localName = "PaisDestinoRecursos")
    private String paisDestinoRecursos;

    // --- NUEVOS CAMPOS EXCLUSIVOS DEL REPORTE 45 (Ajustados a tu XSD) ---
    @JacksonXmlProperty(localName = "NumeroUnicoTransaccion")
    private String numeroUnicoTransaccion;

    @JacksonXmlProperty(localName = "PaisOrigenCliente")
    private String paisOrigenCliente;

    @JacksonXmlProperty(localName = "CiudadOrigenCliente")
    private String ciudadOrigenCliente;

    @JacksonXmlProperty(localName = "NombreClienteReceptorRemitente")
    private String nombreClienteReceptorRemitente;

    @JacksonXmlProperty(localName = "PrimerApellidoReceptorRemitente")
    private String primerApellidoReceptorRemitente;

    @JacksonXmlProperty(localName = "SegundoApellidoReceptorRemitente")
    private String segundoApellidoReceptorRemitente;

    @JacksonXmlProperty(localName = "NombreEmpresaClienteReceptorRemitente")
    private String nombreEmpresaClienteReceptorRemitente;

    @JacksonXmlProperty(localName = "PaisDestinoReceptorRemitente")
    private String paisDestinoReceptorRemitente;

    @JacksonXmlProperty(localName = "CiudadDestinoReceptorRemitente")
    private String ciudadDestinoReceptorRemitente;

    @JacksonXmlProperty(localName = "EntidadExteriorTramitaRemesa")
    private String entidadExteriorTramitaRemesa;

    @JacksonXmlProperty(localName = "DestinoRecursos")
    private String destinoRecursos;
}