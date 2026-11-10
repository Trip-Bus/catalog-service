package com.tripbus.catalog.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * US-138: desglose del precio de un tramo de un viaje para uno o más pasajeros (ver README, "Reglas de precio").
 * US-141: con la categoría del asiento, cada pasajero suma el recargo de su empresa.
 */
public record PriceBreakdown(
        UUID itineraryId,
        int fromOrden,
        int toOrden,
        String origen,
        String destino,
        Instant salida,
        Instant llegada,
        String moneda,
        @Schema(description = "Base del recorrido completo: el precio de la oferta activa si hay; si no, el precio base")
        BigDecimal precioBase,
        boolean conOferta,
        @Schema(description = "Precio del tramo para un adulto, antes de la temporada")
        BigDecimal precioTramo,
        @Schema(description = "Temporada aplicada (null si ninguna)", nullable = true)
        TemporadaAplicada temporada,
        List<PrecioPasajero> pasajeros,
        BigDecimal total) {

    public record TemporadaAplicada(UUID id, String nombre, BigDecimal multiplicador) {
    }

    /**
     * precioTramo ya incluye la temporada; precio = precioTramo con el descuento del tipo de pasajero + el recargo
     * de la categoría del asiento.
     */
    public record PrecioPasajero(String tipo, String seatCategoria, BigDecimal precioTramo,
                                 BigDecimal descuentoPorcentaje, BigDecimal recargoCategoria, BigDecimal precio) {
    }
}
