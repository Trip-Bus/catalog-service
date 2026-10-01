package com.tripbus.catalog.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ItineraryDetail(
        UUID itineraryId,
        UUID routeId,
        UUID busId,
        UUID companyId,
        String empresa,
        String placa,
        String tipoBus,
        int capacidad,
        String origen,
        String destino,
        Instant fechaSalida,
        Instant fechaLlegadaEstimada,
        String estado,
        BigDecimal precioBase,
        String moneda,
        OfertaResumen ofertaActiva,
        List<StopResponse> paradas) {
}
