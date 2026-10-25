package com.tripbus.catalog.web.dto;

import com.tripbus.catalog.domain.ItineraryChange;

import java.time.Instant;
import java.util.UUID;

/** Una fila del historial de cambios de un viaje (GET /itineraries/{id}/changes). */
public record ItineraryChangeResponse(
        UUID id,
        String tipo,
        Instant salidaAnterior,
        Instant salidaNueva,
        UUID busAnterior,
        UUID busNuevo,
        String motivo,
        UUID hechoPor,
        Instant creadoEn) {

    public static ItineraryChangeResponse from(ItineraryChange c) {
        return new ItineraryChangeResponse(c.getId(), c.getTipo().name(), c.getSalidaAnterior(), c.getSalidaNueva(),
                c.getBusAnterior(), c.getBusNuevo(), c.getMotivo(), c.getHechoPor(), c.getCreadoEn());
    }
}
