package com.tripbus.catalog.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Un resultado de búsqueda (US-007/US-008/US-014). */
public record TripSearchResult(
        UUID itineraryId,
        UUID routeId,
        UUID busId,
        String empresa,
        String origen,
        String destino,
        Instant fechaSalida,
        Instant fechaLlegadaEstimada,
        int duracionMinutos,
        BigDecimal precioBase,
        String moneda,
        String tipoBus,
        int capacidad,
        @Schema(description = "Asientos libres según Seat Inventory; igual a capacidad si aún nadie ha ocupado "
                + "asientos del viaje. null solo si Seat Inventory no respondió (disponibilidadConocida = false).",
                nullable = true)
        Integer asientosDisponibles,
        @Schema(description = "true si no quedan asientos libres. null solo si Seat Inventory no respondió "
                + "(disponibilidadConocida = false).", nullable = true)
        Boolean agotado,
        OfertaResumen ofertaActiva,
        @Schema(description = "true si asientosDisponibles y agotado vienen de Seat Inventory; false si Seat "
                + "Inventory no respondió y ambos llegan en null.")
        boolean disponibilidadConocida) {

    /** Una oferta que venció mientras el resultado estaba en cache ya no se muestra. */
    public TripSearchResult sinOfertaVencida(Instant now) {
        if (ofertaActiva == null || ofertaActiva.fin() == null || ofertaActiva.fin().isAfter(now)) {
            return this;
        }
        return new TripSearchResult(itineraryId, routeId, busId, empresa, origen, destino, fechaSalida,
                fechaLlegadaEstimada, duracionMinutos, precioBase, moneda, tipoBus, capacidad,
                asientosDisponibles, agotado, null, disponibilidadConocida);
    }

    /** disponibles = null significa que Seat Inventory no respondió: la disponibilidad queda desconocida. */
    public TripSearchResult withDisponibilidad(Integer disponibles) {
        Boolean agotadoCalc = disponibles == null ? null : disponibles <= 0;
        return new TripSearchResult(itineraryId, routeId, busId, empresa, origen, destino, fechaSalida,
                fechaLlegadaEstimada, duracionMinutos, precioBase, moneda, tipoBus, capacidad,
                disponibles, agotadoCalc, ofertaActiva, disponibles != null);
    }
}
