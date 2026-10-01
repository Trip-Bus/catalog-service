package com.tripbus.catalog.web.dto;

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
        Integer asientosDisponibles,
        Boolean agotado,
        OfertaResumen ofertaActiva) {

    /** Una oferta que venció mientras el resultado estaba en cache ya no se muestra. */
    public TripSearchResult sinOfertaVencida(Instant now) {
        if (ofertaActiva == null || ofertaActiva.fin() == null || ofertaActiva.fin().isAfter(now)) {
            return this;
        }
        return new TripSearchResult(itineraryId, routeId, busId, empresa, origen, destino, fechaSalida,
                fechaLlegadaEstimada, duracionMinutos, precioBase, moneda, tipoBus, capacidad,
                asientosDisponibles, agotado, null);
    }

    public TripSearchResult withDisponibilidad(Integer disponibles) {
        Boolean agotadoCalc = disponibles == null ? null : disponibles <= 0;
        return new TripSearchResult(itineraryId, routeId, busId, empresa, origen, destino, fechaSalida,
                fechaLlegadaEstimada, duracionMinutos, precioBase, moneda, tipoBus, capacidad,
                disponibles, agotadoCalc, ofertaActiva);
    }
}
