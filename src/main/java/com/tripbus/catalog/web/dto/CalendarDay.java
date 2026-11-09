package com.tripbus.catalog.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/** US-136: un día del calendario de precios entre dos ciudades. */
public record CalendarDay(
        LocalDate fecha,
        @Schema(description = "Precio más bajo del día para un adulto (con la oferta activa si hay); null sin viajes",
                nullable = true)
        BigDecimal precioMinimo,
        int viajes,
        @Schema(description = "true si algún viaje del día tiene asientos libres; null si Seat Inventory no respondió",
                nullable = true)
        Boolean hayCupo) {
}
