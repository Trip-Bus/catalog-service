package com.tripbus.catalog.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** JSON público previo a US-135; mantiene estable la forma usada por el frontend. */
public record PublicTripSearchResult(UUID itineraryId, UUID routeId, UUID busId, String empresa, String origen,
        String destino, Instant fechaSalida, Instant fechaLlegadaEstimada, int duracionMinutos, BigDecimal precioBase,
        String moneda, String tipoBus, int capacidad, Integer asientosDisponibles, Boolean agotado,
        OfertaResumen ofertaActiva, boolean disponibilidadConocida) {
    public static PublicTripSearchResult from(TripSearchResult r) {
        return new PublicTripSearchResult(r.itineraryId(), r.routeId(), r.busId(), r.empresa(), r.origen(), r.destino(),
                r.fechaSalida(), r.fechaLlegadaEstimada(), r.duracionMinutos(), r.precioBase(), r.moneda(), r.tipoBus(),
                r.capacidad(), r.asientosDisponibles(), r.agotado(), r.ofertaActiva(), r.disponibilidadConocida());
    }
}
