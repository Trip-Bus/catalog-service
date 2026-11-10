package com.tripbus.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "itineraries")
public class Itinerary {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bus_id", nullable = false)
    private Bus bus;

    @Column(name = "fecha_salida", nullable = false)
    private Instant fechaSalida;

    @Column(name = "fecha_llegada_estimada", nullable = false)
    private Instant fechaLlegadaEstimada;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItineraryStatus estado = ItineraryStatus.PROGRAMADO;

    @Column(name = "precio_base", nullable = false)
    private BigDecimal precioBase;

    @Column(nullable = false)
    private String moneda = "COP";

    @Column(name = "oferta_id")
    private UUID ofertaId;

    @Column(name = "precio_oferta")
    private BigDecimal precioOferta;

    @Column(name = "oferta_fin")
    private Instant ofertaFin;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    protected Itinerary() {
    }

    public Itinerary(UUID id, Route route, Bus bus, Instant fechaSalida, Instant fechaLlegadaEstimada, BigDecimal precioBase) {
        this.id = id;
        this.route = route;
        this.bus = bus;
        this.fechaSalida = fechaSalida;
        this.fechaLlegadaEstimada = fechaLlegadaEstimada;
        this.precioBase = precioBase;
    }

    /** Oferta relámpago activa en este momento (US-014). */
    public boolean tieneOfertaActiva(Instant now) {
        return ofertaId != null && (ofertaFin == null || ofertaFin.isAfter(now));
    }

    public void marcarOferta(UUID ofertaId, BigDecimal precioOferta, Instant ofertaFin) {
        this.ofertaId = ofertaId;
        this.precioOferta = precioOferta;
        this.ofertaFin = ofertaFin;
    }

    public void desmarcarOferta() {
        this.ofertaId = null;
        this.precioOferta = null;
        this.ofertaFin = null;
    }

    public void cancelar() {
        this.estado = ItineraryStatus.CANCELADO;
    }

    /** US-118: nueva salida y su llegada estimada. */
    public void reprogramar(Instant fechaSalida, Instant fechaLlegadaEstimada) {
        this.fechaSalida = fechaSalida;
        this.fechaLlegadaEstimada = fechaLlegadaEstimada;
    }

    /** US-119: el viaje pasa a otro bus. */
    public void cambiarBus(Bus bus) {
        this.bus = bus;
    }

    public UUID getId() {
        return id;
    }

    public Route getRoute() {
        return route;
    }

    public Bus getBus() {
        return bus;
    }

    public Instant getFechaSalida() {
        return fechaSalida;
    }

    public Instant getFechaLlegadaEstimada() {
        return fechaLlegadaEstimada;
    }

    public ItineraryStatus getEstado() {
        return estado;
    }

    public BigDecimal getPrecioBase() {
        return precioBase;
    }

    public String getMoneda() {
        return moneda;
    }

    public UUID getOfertaId() {
        return ofertaId;
    }

    public BigDecimal getPrecioOferta() {
        return precioOferta;
    }

    public Instant getOfertaFin() {
        return ofertaFin;
    }
}
