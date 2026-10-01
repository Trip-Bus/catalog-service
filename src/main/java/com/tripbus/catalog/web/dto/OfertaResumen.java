package com.tripbus.catalog.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OfertaResumen(UUID offerId, BigDecimal precioOferta, Instant fin) {
}
