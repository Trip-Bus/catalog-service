package com.tripbus.catalog.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * POST /internal/pricing (US-138/US-141): con este precio cobra booking. Contrato acordado con booking-payment-service
 * ({@code CatalogClient.PricingRequest} / {@code PricingResult}).
 */
public final class InternalPricing {

    private InternalPricing() {
    }

    /** fromOrden/toOrden null = el recorrido completo. tipoPasajero null = ADULTO. */
    public record Request(UUID itineraryId, Integer fromOrden, Integer toOrden, List<Pasajero> pasajeros) {
    }

    public record Pasajero(String asiento, String tipoPasajero) {
    }

    /** estado: PROGRAMADO o CANCELADO (booking no vende un viaje que no esté PROGRAMADO). */
    public record Response(UUID itineraryId, String estado, String moneda, Tramo tramo, List<Precio> pasajeros,
                           BigDecimal total) {
    }

    public record Tramo(int fromOrden, String paradaAbordaje, int toOrden, String paradaBajada) {
    }

    /**
     * Un precio por pasajero, en el orden del pedido: precio = precioBase - descuentoTipo + recargoCategoria.
     * precioBase ya trae el tramo y la temporada; descuentoTipo (COP) es el del tipo de pasajero; recargoCategoria
     * (COP) el de la categoría del asiento en el mapa del bus.
     */
    public record Precio(String asiento, String tipoPasajero, String categoriaAsiento, BigDecimal precioBase,
                         BigDecimal descuentoTipo, BigDecimal recargoCategoria, BigDecimal precio) {
    }
}
