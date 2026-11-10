package com.tripbus.catalog.web.dto;

import com.tripbus.catalog.domain.FareSeason;
import com.tripbus.catalog.domain.SegmentPrice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Entradas y salidas de las tarifas (US-138: temporadas, tipos de pasajero y precios por tramo; US-141: recargos
 * por categoría de asiento). companyId solo lo envía el ADMIN; el OPERADOR usa la de su token.
 */
public final class FareDtos {

    private FareDtos() {
    }

    public record SeasonRequest(UUID companyId, UUID routeId, String nombre, LocalDate desde, LocalDate hasta,
                                BigDecimal multiplicador) {
    }

    public record SeasonResponse(UUID id, UUID companyId, UUID routeId, String nombre, LocalDate desde,
                                 LocalDate hasta, BigDecimal multiplicador) {

        public static SeasonResponse from(FareSeason s) {
            return new SeasonResponse(s.getId(), s.getCompanyId(), s.getRouteId(), s.getNombre(), s.getDesde(),
                    s.getHasta(), s.getMultiplicador());
        }
    }

    public record DiscountRequest(UUID companyId, BigDecimal descuentoPorcentaje) {
    }

    public record PassengerTypeResponse(String tipo, BigDecimal descuentoPorcentaje) {
    }

    public record SurchargeRequest(UUID companyId, BigDecimal recargo) {
    }

    public record SurchargeResponse(String categoria, BigDecimal recargo) {
    }

    public record SegmentPriceRequest(Integer desdeOrden, Integer hastaOrden, BigDecimal precio) {
    }

    public record SegmentPriceResponse(int desdeOrden, int hastaOrden, BigDecimal precio) {

        public static SegmentPriceResponse from(SegmentPrice p) {
            return new SegmentPriceResponse(p.getDesdeOrden(), p.getHastaOrden(), p.getPrecio());
        }
    }
}
