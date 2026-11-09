package com.tripbus.catalog.web.dto;

import com.tripbus.catalog.domain.CategoriaAsiento;
import com.tripbus.catalog.domain.TipoPasajero;

import java.util.List;
import java.util.UUID;

/**
 * POST /internal/pricing (US-138/US-141): booking cotiza lo que cobra. fromOrden/toOrden opcionales (sin ellos,
 * el recorrido completo); seatCategoria opcional (sin ella, sin recargo).
 */
public record PricingRequest(UUID itineraryId, Integer fromOrden, Integer toOrden, List<Pasajero> pasajeros) {

    public record Pasajero(TipoPasajero tipo, CategoriaAsiento seatCategoria) {
    }
}
