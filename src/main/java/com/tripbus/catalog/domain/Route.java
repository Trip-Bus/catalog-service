package com.tripbus.catalog.domain;

import com.tripbus.catalog.service.TextNormalizer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "routes")
public class Route {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(nullable = false)
    private String origen;

    @Column(nullable = false)
    private String destino;

    @Column(name = "origen_norm", nullable = false)
    private String origenNorm;

    @Column(name = "destino_norm", nullable = false)
    private String destinoNorm;

    @Column(name = "duracion_minutos", nullable = false)
    private int duracionMinutos;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RouteStatus estado = RouteStatus.ACTIVA;

    protected Route() {
    }

    public Route(UUID id, Company company, String origen, String destino, int duracionMinutos) {
        this.id = id;
        this.company = company;
        actualizar(origen, destino, duracionMinutos);
    }

    /** Cambia origen/destino (con sus formas normalizadas para la búsqueda) y la duración. */
    public final void actualizar(String origen, String destino, int duracionMinutos) {
        this.origen = origen;
        this.destino = destino;
        this.origenNorm = TextNormalizer.normalize(origen);
        this.destinoNorm = TextNormalizer.normalize(destino);
        this.duracionMinutos = duracionMinutos;
    }

    public void cambiarEstado(RouteStatus estado) {
        this.estado = estado;
    }

    public UUID getId() {
        return id;
    }

    public Company getCompany() {
        return company;
    }

    public String getOrigen() {
        return origen;
    }

    public String getDestino() {
        return destino;
    }

    public int getDuracionMinutos() {
        return duracionMinutos;
    }

    public RouteStatus getEstado() {
        return estado;
    }
}
