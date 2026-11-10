package com.tripbus.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** US-138: temporada de una empresa (o de una de sus rutas): multiplica el precio entre desde y hasta. */
@Entity
@Table(name = "fare_seasons")
public class FareSeason {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "route_id")
    private UUID routeId;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private LocalDate desde;

    @Column(nullable = false)
    private LocalDate hasta;

    @Column(nullable = false)
    private BigDecimal multiplicador;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected FareSeason() {
    }

    public FareSeason(UUID id, UUID companyId, UUID routeId, String nombre, LocalDate desde, LocalDate hasta,
                      BigDecimal multiplicador, Instant creadoEn) {
        this.id = id;
        this.companyId = companyId;
        this.creadoEn = creadoEn;
        asignar(routeId, nombre, desde, hasta, multiplicador);
    }

    public void actualizar(UUID routeId, String nombre, LocalDate desde, LocalDate hasta, BigDecimal multiplicador) {
        asignar(routeId, nombre, desde, hasta, multiplicador);
    }

    // Privado (no sobrescribible por el proxy de Hibernate) para poder usarlo desde el constructor.
    private void asignar(UUID routeId, String nombre, LocalDate desde, LocalDate hasta, BigDecimal multiplicador) {
        this.routeId = routeId;
        this.nombre = nombre;
        this.desde = desde;
        this.hasta = hasta;
        this.multiplicador = multiplicador;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getRouteId() {
        return routeId;
    }

    public String getNombre() {
        return nombre;
    }

    public LocalDate getDesde() {
        return desde;
    }

    public LocalDate getHasta() {
        return hasta;
    }

    public BigDecimal getMultiplicador() {
        return multiplicador;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}
