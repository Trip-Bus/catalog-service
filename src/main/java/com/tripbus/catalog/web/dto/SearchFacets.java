package com.tripbus.catalog.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** GET /trips/search/facets: lo que el frontend necesita para armar los filtros de una búsqueda. */
public record SearchFacets(
        @Schema(description = "Mínimo precioDesde de los viajes vigentes del día con los filtros (sin el de precio); "
                + "null sin viajes", nullable = true)
        BigDecimal precioMin,
        @Schema(description = "Máximo precioDesde de los viajes vigentes del día con los filtros (sin el de precio); "
                + "null sin viajes", nullable = true)
        BigDecimal precioMax,
        @Schema(description = "Todas las empresas que cubren la ruta en los próximos 14 días (y las del día pedido), "
                + "ordenadas por nombre")
        List<Empresa> empresas,
        @Schema(description = "Tipos de bus de los viajes vigentes del día (con empresa y franja, sin tipoBus)")
        List<String> tiposBus) {

    /** Una empresa de la ruta y cuántas salidas tiene en la fecha pedida (con franja y tipoBus; puede ser 0). */
    public record Empresa(
            UUID id,
            String nombre,
            @Schema(description = "Terminal de salida en la ciudad de origen; null si sus paradas no tienen terminal",
                    nullable = true)
            String terminal,
            int viajes) {
    }
}
