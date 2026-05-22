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
public class Encabezado {
    @JacksonXmlProperty(localName = "ClaseDato") private int claseDato;
    @JacksonXmlProperty(localName = "VersionClaseDato") private String versionClaseDato;
    @JacksonXmlProperty(localName = "Archivo") private int archivo;
    @JacksonXmlProperty(localName = "VersionArchivo") private String versionArchivo;
    @JacksonXmlProperty(localName = "Periodo") private String periodo;
    @JacksonXmlProperty(localName = "IdEntidad") private String idEntidad;
    @JacksonXmlProperty(localName = "TipoCarga") private int tipoCarga;
    @JacksonXmlProperty(localName = "TipoMoneda") private int tipoMoneda;

}