package org.apache.fineract.infrastructure.report.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@JacksonXmlRootElement(localName = "ArchivoSICVECA")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ArchivoSICVECA {

    @JacksonXmlProperty(localName = "Encabezado")
    private Encabezado encabezado;

    @JacksonXmlProperty(localName = "Registro")
    @JacksonXmlElementWrapper(localName = "Datos")
    private List<Registro> datos;
}