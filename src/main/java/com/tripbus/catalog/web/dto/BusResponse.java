package com.tripbus.catalog.web.dto;

import java.util.List;
import com.tripbus.catalog.domain.Bus;

import java.time.Instant;
import java.util.UUID;

public record BusResponse(
        UUID id,
        UUID companyId,
        String placa,
        String tipo,
        int capacidad,
        String estado,
        List<String> servicios,
        Instant creadoEn,
        Instant actualizadoEn) {

    public static BusResponse from(Bus b) {
        return new BusResponse(b.getId(), b.getCompanyId(), b.getPlaca(), b.getTipo().name(), b.getCapacidad(),
                b.getEstado().name(), b.getServicios().stream().map(Enum::name).toList(), b.getCreadoEn(),
                b.getActualizadoEn());
    }
}
