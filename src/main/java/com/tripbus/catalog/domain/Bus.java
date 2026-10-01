package com.tripbus.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

    protected Bus() {
    }

    public Bus(UUID id, UUID companyId, String placa, BusType tipo, int capacidad) {
        this.id = id;
        this.companyId = companyId;
        this.placa = placa;
        this.tipo = tipo;
        this.capacidad = capacidad;
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
}
