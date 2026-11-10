package com.tripbus.catalog.web.dto;

import com.tripbus.catalog.domain.Company;

import java.time.Instant;
import java.util.UUID;

public record CompanyResponse(
        UUID id,
        String nombre,
        String nit,
        String estado,
        Instant creadoEn,
        Instant actualizadoEn) {

    public static CompanyResponse from(Company c) {
        return new CompanyResponse(c.getId(), c.getNombre(), c.getNit(), c.getEstado().name(), c.getCreadoEn(),
                c.getActualizadoEn());
    }
}
