package com.tripbus.catalog.web.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** US-116: un viaje. La llegada estimada es fechaSalida + duración de la ruta. precioBase en COP. */
public record CreateItineraryRequest(
        @NotNull(message = "routeId es obligatorio") UUID routeId,
        @NotNull(message = "busId es obligatorio") UUID busId,
        @NotNull(message = "fechaSalida es obligatoria") Instant fechaSalida,
        @NotNull(message = "precioBase es obligatorio") BigDecimal precioBase) {
}
