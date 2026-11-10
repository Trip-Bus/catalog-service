package com.tripbus.catalog.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * US-138: reglas de precio, sin estado (las usan la búsqueda por tramo y el desglose de {@link PricingService}).
 *
 * <ol>
 *   <li>Base del recorrido completo: el precio de la oferta activa si hay; si no, el precio base del viaje.</li>
 *   <li>Tramo: base × (minutos entre las dos paradas / duración total), redondeado a 100 COP y nunca menos del
 *       {@code minPorcentaje} % de la base (redondeado hacia arriba a 100). Si el tramo tiene precio exacto en
 *       segment_prices (y no hay oferta activa), se usa ese precio.</li>
 *   <li>Temporada: tramo × multiplicador, redondeado a 100.</li>
 *   <li>Tipo de pasajero: × (1 − descuento %), redondeado a 100.</li>
 *   <li>Categoría del asiento: + recargo en COP.</li>
 * </ol>
 */
public final class PricingRules {

    static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private PricingRules() {
    }

    /** Redondea al múltiplo de 100 COP más cercano (la mitad hacia arriba). */
    public static BigDecimal redondear(BigDecimal valor) {
        return valor.divide(CIEN, 0, RoundingMode.HALF_UP).multiply(CIEN);
    }

    /**
     * Precio del tramo sobre la base del recorrido completo. {@code exacto} (puede ser null) es el precio fijado
     * para el tramo; el recorrido completo siempre vale la base.
     */
    public static BigDecimal tramo(BigDecimal base, int minutosDesde, int minutosHasta, int duracionTotal,
                                   BigDecimal exacto, int minPorcentaje) {
        if (minutosDesde == 0 && minutosHasta == duracionTotal) {
            return base;
        }
        if (exacto != null) {
            return exacto;
        }
        BigDecimal proporcional = redondear(base.multiply(BigDecimal.valueOf(minutosHasta - minutosDesde))
                .divide(BigDecimal.valueOf(duracionTotal), 2, RoundingMode.HALF_UP));
        BigDecimal minimo = base.multiply(BigDecimal.valueOf(minPorcentaje))
                .divide(CIEN, 0, RoundingMode.CEILING)
                .divide(CIEN, 0, RoundingMode.CEILING).multiply(CIEN);
        return proporcional.max(minimo).min(base);
    }

    /** Temporada: multiplicador (1 sin temporada), redondeado a 100. */
    public static BigDecimal conTemporada(BigDecimal tramo, BigDecimal multiplicador) {
        return redondear(tramo.multiply(multiplicador));
    }

    /** Tipo de pasajero: descuento en porcentaje (0 a 100), redondeado a 100. */
    public static BigDecimal conDescuento(BigDecimal precio, BigDecimal descuentoPorcentaje) {
        return redondear(precio.multiply(CIEN.subtract(descuentoPorcentaje)).divide(CIEN, 2, RoundingMode.HALF_UP));
    }
}
