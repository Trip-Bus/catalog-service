package com.tripbus.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "companies")
public class Company {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String nombre;

    private String nit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompanyStatus estado = CompanyStatus.ACTIVA;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn = creadoEn;

    protected Company() {
    }

    public Company(UUID id, String nombre, String nit) {
        this.id = id;
        this.nombre = nombre;
        this.nit = nit;
    }

    public void actualizar(String nombre, String nit, Instant ahora) {
        this.nombre = nombre;
        this.nit = nit;
        this.actualizadoEn = ahora;
    }

    public void cambiarEstado(CompanyStatus estado, Instant ahora) {
        this.estado = estado;
        this.actualizadoEn = ahora;
    }

    public boolean isActiva() {
        return estado == CompanyStatus.ACTIVA;
    }

    public UUID getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getNit() {
        return nit;
    }

    public CompanyStatus getEstado() {
        return estado;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
