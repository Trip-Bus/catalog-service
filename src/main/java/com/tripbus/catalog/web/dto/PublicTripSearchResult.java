package com.tripbus.catalog.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JSON público de un resultado de búsqueda; mantiene estable la forma usada por el frontend (la previa a US-135).
 * US-113 agrega servicios. US-138: fromOrden, toOrden, salida, llegada y precio solo vienen cuando el pasajero
 * viaja un tramo (origen o destino son paradas intermedias); en el recorrido completo no aparecen.
 */
public record PublicTripSearchResult(UUID itineraryId, UUID routeId, UUID busId, String empresa, String origen,
        String destino, Instant fechaSalida, Instant fechaLlegadaEstimada, int duracionMinutos, BigDecimal precioBase,
        String moneda, String tipoBus, int capacidad, Integer asientosDisponibles, Boolean agotado,
        OfertaResumen ofertaActiva, boolean disponibilidadConocida,
        @Schema(description = "US-113: servicios a bordo del bus") List<String> servicios,
        @Schema(description = "US-138: orden de la parada donde sube el pasajero (solo en un tramo)")
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer fromOrden,
        @Schema(description = "US-138: orden de la parada donde baja el pasajero (solo en un tramo)")
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer toOrden,
        @Schema(description = "US-138: hora de salida desde la parada del pasajero (solo en un tramo)")
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant salida,
        @Schema(description = "US-138: hora de llegada a la parada del pasajero (solo en un tramo)")
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant llegada,
        @Schema(description = "US-138: precio del tramo para un adulto, sobre la oferta activa si hay (solo en un tramo)")
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal precio) {

    public static PublicTripSearchResult from(TripSearchResult r) {
        return new PublicTripSearchResult(r.itineraryId(), r.routeId(), r.busId(), r.empresa(), r.origen(), r.destino(),
                r.fechaSalida(), r.fechaLlegadaEstimada(), r.duracionMinutos(), r.precioBase(), r.moneda(), r.tipoBus(),
                r.capacidad(), r.asientosDisponibles(), r.agotado(), r.ofertaActiva(), r.disponibilidadConocida(),
                r.servicios(), r.fromOrden(), r.toOrden(), r.salida(), r.llegada(), r.precio());
    }
}
