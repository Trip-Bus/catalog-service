package com.tripbus.catalog.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tripbus.catalog.domain.Route;

import java.util.List;
import java.util.UUID;

/** Ruta (US-114). paradas solo viene en el detalle, no en el listado. */
public record RouteResponse(
        UUID id,
        UUID companyId,
        String empresa,
        String origen,
        String destino,
        int duracionMinutos,
        String estado,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<StopResponse> paradas) {

    public static RouteResponse from(Route r, List<StopResponse> paradas) {
        return new RouteResponse(r.getId(), r.getCompany().getId(), r.getCompany().getNombre(), r.getOrigen(),
                r.getDestino(), r.getDuracionMinutos(), r.getEstado().name(), paradas);
    }
}
