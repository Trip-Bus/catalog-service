package com.tripbus.catalog.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Un resultado de búsqueda (US-007/US-008/US-014). US-113: servicios del bus. US-138: si el pasajero viaja solo
 * un tramo (origen o destino son paradas intermedias), fromOrden/toOrden, salida/llegada del tramo y su precio;
 * en el recorrido completo esos campos van en null.
 */
public record TripSearchResult(
        UUID itineraryId,
        UUID routeId,
        UUID busId,
        String empresa,
        String origen,
        String destino,
        Instant fechaSalida,
        Instant fechaLlegadaEstimada,
        int duracionMinutos,
        BigDecimal precioBase,
        String moneda,
        String tipoBus,
        int capacidad,
        @Schema(description = "Asientos libres según Seat Inventory; igual a capacidad si aún nadie ha ocupado "
                + "asientos del viaje. null solo si Seat Inventory no respondió (disponibilidadConocida = false).",
                nullable = true)
        Integer asientosDisponibles,
        @Schema(description = "true si no quedan asientos libres. null solo si Seat Inventory no respondió "
                + "(disponibilidadConocida = false).", nullable = true)
        Boolean agotado,
        OfertaResumen ofertaActiva,
        @Schema(description = "true si asientosDisponibles y agotado vienen de Seat Inventory; false si Seat "
                + "Inventory no respondió y ambos llegan en null.")
        boolean disponibilidadConocida,
        UUID companyId,
        List<String> servicios,
        Integer fromOrden,
        Integer toOrden,
        Instant salida,
        Instant llegada,
        BigDecimal precio) {

    /** Constructor compatible con los resultados creados por consumidores sin companyId. */
    public TripSearchResult(UUID itineraryId, UUID routeId, UUID busId, String empresa, String origen, String destino,
            Instant fechaSalida, Instant fechaLlegadaEstimada, int duracionMinutos, BigDecimal precioBase, String moneda,
            String tipoBus, int capacidad, Integer asientosDisponibles, Boolean agotado, OfertaResumen ofertaActiva,
            boolean disponibilidadConocida) {
        this(itineraryId, routeId, busId, empresa, origen, destino, fechaSalida, fechaLlegadaEstimada, duracionMinutos,
                precioBase, moneda, tipoBus, capacidad, asientosDisponibles, agotado, ofertaActiva,
                disponibilidadConocida, null);
    }

    /** Constructor compatible con los resultados anteriores a US-113/US-138 (recorrido completo, sin servicios). */
    public TripSearchResult(UUID itineraryId, UUID routeId, UUID busId, String empresa, String origen, String destino,
            Instant fechaSalida, Instant fechaLlegadaEstimada, int duracionMinutos, BigDecimal precioBase, String moneda,
            String tipoBus, int capacidad, Integer asientosDisponibles, Boolean agotado, OfertaResumen ofertaActiva,
            boolean disponibilidadConocida, UUID companyId) {
        this(itineraryId, routeId, busId, empresa, origen, destino, fechaSalida, fechaLlegadaEstimada, duracionMinutos,
                precioBase, moneda, tipoBus, capacidad, asientosDisponibles, agotado, ofertaActiva,
                disponibilidadConocida, companyId, List.of(), null, null, null, null, null);
    }

    /** US-138: el pasajero viaja solo un tramo del recorrido. */
    public boolean esTramo() {
        return fromOrden != null;
    }

    /** Hora en que el pasajero sube: la del tramo, o la del viaje. */
    public Instant salidaEfectiva() {
        return esTramo() ? salida : fechaSalida;
    }

    /** Minutos de viaje del pasajero: los del tramo, o los del recorrido. */
    public int duracionEfectiva() {
        return esTramo() ? (int) Duration.between(salida, llegada).toMinutes() : duracionMinutos;
    }

    /** Lo que paga un adulto: el precio del tramo, o el de la oferta activa, o el base. */
    public BigDecimal precioEfectivo() {
        if (esTramo()) {
            return precio;
        }
        return ofertaActiva == null ? precioBase : ofertaActiva.precioOferta();
    }

    /** US-138: el mismo viaje, pero para el tramo fromOrden → toOrden. */
    public TripSearchResult conTramo(int desde, int hasta, Instant salidaTramo, Instant llegadaTramo,
                                     BigDecimal precioTramo) {
        return new TripSearchResult(itineraryId, routeId, busId, empresa, origen, destino, fechaSalida,
                fechaLlegadaEstimada, duracionMinutos, precioBase, moneda, tipoBus, capacidad, asientosDisponibles,
                agotado, ofertaActiva, disponibilidadConocida, companyId, servicios, desde, hasta, salidaTramo,
                llegadaTramo, precioTramo);
    }

    /** Una oferta que venció mientras el resultado estaba en cache ya no se muestra. */
    public TripSearchResult sinOfertaVencida(Instant now) {
        if (ofertaActiva == null || ofertaActiva.fin() == null || ofertaActiva.fin().isAfter(now)) {
            return this;
        }
        return new TripSearchResult(itineraryId, routeId, busId, empresa, origen, destino, fechaSalida,
                fechaLlegadaEstimada, duracionMinutos, precioBase, moneda, tipoBus, capacidad,
                asientosDisponibles, agotado, null, disponibilidadConocida, companyId, servicios, fromOrden, toOrden,
                salida, llegada, precio);
    }

    /** disponibles = null significa que Seat Inventory no respondió: la disponibilidad queda desconocida. */
    public TripSearchResult withDisponibilidad(Integer disponibles) {
        Boolean agotadoCalc = disponibles == null ? null : disponibles <= 0;
        return new TripSearchResult(itineraryId, routeId, busId, empresa, origen, destino, fechaSalida,
                fechaLlegadaEstimada, duracionMinutos, precioBase, moneda, tipoBus, capacidad,
                disponibles, agotadoCalc, ofertaActiva, disponibles != null, companyId, servicios, fromOrden, toOrden,
                salida, llegada, precio);
    }
}
