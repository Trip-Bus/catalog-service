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
@Table(name = "buses")
public class Bus {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(nullable = false, unique = true)
    private String placa;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BusType tipo;

    @Column(nullable = false)
    private int capacidad;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BusStatus estado = BusStatus.ACTIVO;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn = creadoEn;

    protected Bus() {
    }

    public Bus(UUID id, UUID companyId, String placa, BusType tipo, int capacidad) {
        this.id = id;
        this.companyId = companyId;
        this.placa = placa;
        this.tipo = tipo;
        this.capacidad = capacidad;
    }

    public void actualizar(String placa, BusType tipo, Instant ahora) {
        this.placa = placa;
        this.tipo = tipo;
        this.actualizadoEn = ahora;
    }

    public void cambiarCapacidad(int capacidad, Instant ahora) {
        this.capacidad = capacidad;
        this.actualizadoEn = ahora;
    }

    public void cambiarEstado(BusStatus estado, Instant ahora) {
        this.estado = estado;
        this.actualizadoEn = ahora;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getPlaca() {
        return placa;
    }

    public BusType getTipo() {
        return tipo;
    }

    public int getCapacidad() {
        return capacidad;
    }

    public BusStatus getEstado() {
        return estado;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
