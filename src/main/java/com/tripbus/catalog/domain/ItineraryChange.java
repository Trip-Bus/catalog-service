package com.tripbus.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Un cambio en un viaje: retraso, reprogramación, cambio de bus o cancelación (US-118/US-119). */
@Entity
@Table(name = "itinerary_changes")
public class ItineraryChange {

    @Id
    private UUID id;

    @Column(name = "itinerary_id", nullable = false)
    private UUID itineraryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChangeType tipo;

    @Column(name = "salida_anterior")
    private Instant salidaAnterior;

    @Column(name = "salida_nueva")
    private Instant salidaNueva;

    @Column(name = "bus_anterior")
    private UUID busAnterior;

    @Column(name = "bus_nuevo")
    private UUID busNuevo;

    @Column(nullable = false)
    private String motivo;

    @Column(name = "hecho_por")
    private UUID hechoPor;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected ItineraryChange() {
    }

    @SuppressWarnings("java:S107") // Una fila del historial: todos sus campos se conocen al crearla.
    public ItineraryChange(UUID itineraryId, ChangeType tipo, Instant salidaAnterior, Instant salidaNueva,
                           UUID busAnterior, UUID busNuevo, String motivo, UUID hechoPor, Instant creadoEn) {
        this.id = UUID.randomUUID();
        this.itineraryId = itineraryId;
        this.tipo = tipo;
        this.salidaAnterior = salidaAnterior;
        this.salidaNueva = salidaNueva;
        this.busAnterior = busAnterior;
        this.busNuevo = busNuevo;
        this.motivo = motivo;
        this.hechoPor = hechoPor;
        this.creadoEn = creadoEn;
    }

    public UUID getId() {
        return id;
    }

    public UUID getItineraryId() {
        return itineraryId;
    }

    public ChangeType getTipo() {
        return tipo;
    }

    public Instant getSalidaAnterior() {
        return salidaAnterior;
    }

    public Instant getSalidaNueva() {
        return salidaNueva;
    }

    public UUID getBusAnterior() {
        return busAnterior;
    }

    public UUID getBusNuevo() {
        return busNuevo;
    }

    public String getMotivo() {
        return motivo;
    }

    public UUID getHechoPor() {
        return hechoPor;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}
