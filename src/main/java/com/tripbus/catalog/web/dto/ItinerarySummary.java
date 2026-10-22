package com.tripbus.catalog.web.dto;

import com.tripbus.catalog.domain.Itinerary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Viaje en los listados del operador y en la respuesta de la programación (US-116). */
public record ItinerarySummary(
        UUID itineraryId,
        UUID routeId,
        UUID busId,
        UUID companyId,
        String empresa,
        String origen,
        String destino,
        String placa,
        Instant fechaSalida,
        Instant fechaLlegadaEstimada,
        String estado,
        BigDecimal precioBase,
        String moneda) {

    public static ItinerarySummary from(Itinerary i) {
        return new ItinerarySummary(i.getId(), i.getRoute().getId(), i.getBus().getId(),
                i.getRoute().getCompany().getId(), i.getRoute().getCompany().getNombre(), i.getRoute().getOrigen(),
                i.getRoute().getDestino(), i.getBus().getPlaca(), i.getFechaSalida(), i.getFechaLlegadaEstimada(),
                i.getEstado().name(), i.getPrecioBase(), i.getMoneda());
    }
}
