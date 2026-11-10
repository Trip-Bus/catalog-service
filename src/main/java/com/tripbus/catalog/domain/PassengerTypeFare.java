package com.tripbus.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/** US-138: descuento de una empresa para un tipo de pasajero. Sin fila, el descuento es 0 %. */
@Entity
@Table(name = "fare_passenger_types")
public class PassengerTypeFare {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoPasajero tipo;

    @Column(name = "descuento_porcentaje", nullable = false)
    private BigDecimal descuentoPorcentaje;

    protected PassengerTypeFare() {
    }

    public PassengerTypeFare(UUID id, UUID companyId, TipoPasajero tipo, BigDecimal descuentoPorcentaje) {
        this.id = id;
        this.companyId = companyId;
        this.tipo = tipo;
        this.descuentoPorcentaje = descuentoPorcentaje;
    }

    public void cambiarDescuento(BigDecimal descuentoPorcentaje) {
        this.descuentoPorcentaje = descuentoPorcentaje;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public TipoPasajero getTipo() {
        return tipo;
    }

    public BigDecimal getDescuentoPorcentaje() {
        return descuentoPorcentaje;
    }
}
