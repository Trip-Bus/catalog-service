package com.tripbus.catalog.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** US-113: guarda el conjunto de servicios como texto "WIFI,BANO" (orden del enum; vacío = ninguno). */
@Converter
public class ServiciosConverter implements AttributeConverter<Set<ServicioBus>, String> {

    @Override
    public String convertToDatabaseColumn(Set<ServicioBus> servicios) {
        return servicios == null ? "" : conTipo(servicios).stream().map(Enum::name)
                .collect(Collectors.joining(","));
    }

    @Override
    public Set<ServicioBus> convertToEntityAttribute(String texto) {
        EnumSet<ServicioBus> servicios = EnumSet.noneOf(ServicioBus.class);
        if (texto != null) {
            Arrays.stream(texto.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .map(ServicioBus::valueOf).forEach(servicios::add);
        }
        return servicios;
    }

    /** Copia ordenada por el enum (EnumSet itera en ese orden). */
    private static Set<ServicioBus> conTipo(Set<ServicioBus> servicios) {
        EnumSet<ServicioBus> copia = EnumSet.noneOf(ServicioBus.class);
        copia.addAll(servicios);
        return copia;
    }
}
