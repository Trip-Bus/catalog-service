package com.tripbus.catalog.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Filtros, orden y página de /trips/search (US-135) y de /trips/round-trip (US-137). tramos: ver {@link SearchMode}.
 */
public record SearchFilters(UUID companyId, String franja, BigDecimal precioMin, BigDecimal precioMax,
                            String tipoBus, String sort, String order, int page, int size, Boolean tramos) {

    /** Sin filtros: ordenado por salida, primera página de 50, modo AUTO. */
    public static SearchFilters ninguno() {
        return new SearchFilters(null, null, null, null, null, "salida", "asc", 0, 50, null);
    }
}
