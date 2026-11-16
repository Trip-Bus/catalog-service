package com.tripbus.catalog.web.dto;

import com.tripbus.catalog.service.PricingRules;
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
 *
 * <p>Precio "desde" ({@link #precioDesde()}): lo que paga un adulto en un asiento sin recargo, con las mismas reglas
 * que GET /itineraries/{id}/price ({@link PricingRules}): el precio del tramo (o la base del recorrido completo, con
 * la oferta activa si no ha vencido), la temporada del día de salida ({@code multiplicadorTemporada}) y el descuento
 * de la empresa para ADULTO ({@code descuentoAdulto}). {@code precioTramoSinOferta} es el precio del tramo sin la
 * oferta, para cuando la oferta vence con el resultado en cache.
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
        BigDecimal precio,
        BigDecimal precioTramoSinOferta,
        BigDecimal multiplicadorTemporada,
        BigDecimal descuentoAdulto) {

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
                disponibilidadConocida, companyId, List.of(), null, null, null, null, null, null, null, null);
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

    /** Precio del tramo (o del recorrido) antes de temporada y tipo de pasajero: el del tramo, la oferta o el base. */
    public BigDecimal precioEfectivo() {
        if (esTramo()) {
            return precio;
        }
        return ofertaActiva == null ? precioBase : ofertaActiva.precioOferta();
    }

    /**
     * Precio "desde" de la búsqueda, el calendario y las facetas: lo que paga un adulto en un asiento sin recargo
     * (igual al total de GET /itineraries/{id}/price para un ADULTO sin asiento). Sin temporada ni descuento
     * conocidos es el precio efectivo.
     */
    public BigDecimal precioDesde() {
        if (multiplicadorTemporada == null && descuentoAdulto == null) {
            return precioEfectivo();
        }
        BigDecimal conTemporada = PricingRules.conTemporada(precioEfectivo(),
                multiplicadorTemporada == null ? BigDecimal.ONE : multiplicadorTemporada);
        return PricingRules.conDescuento(conTemporada, descuentoAdulto == null ? BigDecimal.ZERO : descuentoAdulto);
    }

    /** La temporada del día de salida y el descuento de ADULTO de la empresa (ver {@link #precioDesde()}). */
    public TripSearchResult conTarifa(BigDecimal multiplicador, BigDecimal descuento) {
        return new TripSearchResult(itineraryId, routeId, busId, empresa, origen, destino, fechaSalida,
                fechaLlegadaEstimada, duracionMinutos, precioBase, moneda, tipoBus, capacidad, asientosDisponibles,
                agotado, ofertaActiva, disponibilidadConocida, companyId, servicios, fromOrden, toOrden, salida,
                llegada, precio, precioTramoSinOferta, multiplicador, descuento);
    }

    /** US-138: el mismo viaje, pero para el tramo fromOrden → toOrden (sin oferta el tramo vale lo mismo). */
    public TripSearchResult conTramo(int desde, int hasta, Instant salidaTramo, Instant llegadaTramo,
                                     BigDecimal precioTramo) {
        return conTramo(desde, hasta, salidaTramo, llegadaTramo, precioTramo, precioTramo);
    }

    /** US-138: el tramo con su precio (sobre la oferta activa si hay) y su precio sin la oferta. */
    public TripSearchResult conTramo(int desde, int hasta, Instant salidaTramo, Instant llegadaTramo,
                                     BigDecimal precioTramo, BigDecimal sinOferta) {
        return new TripSearchResult(itineraryId, routeId, busId, empresa, origen, destino, fechaSalida,
                fechaLlegadaEstimada, duracionMinutos, precioBase, moneda, tipoBus, capacidad, asientosDisponibles,
                agotado, ofertaActiva, disponibilidadConocida, companyId, servicios, desde, hasta, salidaTramo,
                llegadaTramo, precioTramo, sinOferta, multiplicadorTemporada, descuentoAdulto);
    }

    /**
     * Una oferta que venció mientras el resultado estaba en cache ya no se muestra, y el tramo vuelve a su precio
     * sin la oferta.
     */
    public TripSearchResult sinOfertaVencida(Instant now) {
        if (ofertaActiva == null || ofertaActiva.fin() == null || ofertaActiva.fin().isAfter(now)) {
            return this;
        }
        BigDecimal precioTramo = precioTramoSinOferta == null ? precio : precioTramoSinOferta;
        return new TripSearchResult(itineraryId, routeId, busId, empresa, origen, destino, fechaSalida,
                fechaLlegadaEstimada, duracionMinutos, precioBase, moneda, tipoBus, capacidad,
                asientosDisponibles, agotado, null, disponibilidadConocida, companyId, servicios, fromOrden, toOrden,
                salida, llegada, precioTramo, precioTramoSinOferta, multiplicadorTemporada, descuentoAdulto);
    }

    /** disponibles = null significa que Seat Inventory no respondió: la disponibilidad queda desconocida. */
    public TripSearchResult withDisponibilidad(Integer disponibles) {
        Boolean agotadoCalc = disponibles == null ? null : disponibles <= 0;
        return new TripSearchResult(itineraryId, routeId, busId, empresa, origen, destino, fechaSalida,
                fechaLlegadaEstimada, duracionMinutos, precioBase, moneda, tipoBus, capacidad,
                disponibles, agotadoCalc, ofertaActiva, disponibles != null, companyId, servicios, fromOrden, toOrden,
                salida, llegada, precio, precioTramoSinOferta, multiplicadorTemporada, descuentoAdulto);
    }
}
