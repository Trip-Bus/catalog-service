package com.tripbus.catalog.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PriceResponse(
        UUID itineraryId,
        BigDecimal precioBase,
        String moneda,
        OfertaResumen ofertaActiva,
        String estado) {
}
