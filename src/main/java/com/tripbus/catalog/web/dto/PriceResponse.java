package com.tripbus.catalog.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

/** Precio de un viaje. US-138: desglose solo cuando se piden from, to o tipos (sin ellos, la respuesta de siempre). */
public record PriceResponse(
        UUID itineraryId,
        BigDecimal precioBase,
        String moneda,
        OfertaResumen ofertaActiva,
        String estado,
        @JsonInclude(JsonInclude.Include.NON_NULL) PriceBreakdown desglose) {

    public PriceResponse(UUID itineraryId, BigDecimal precioBase, String moneda, OfertaResumen ofertaActiva,
                         String estado) {
        this(itineraryId, precioBase, moneda, ofertaActiva, estado, null);
    }

    public PriceResponse conDesglose(PriceBreakdown desglose) {
        return new PriceResponse(itineraryId, precioBase, moneda, ofertaActiva, estado, desglose);
    }
}
