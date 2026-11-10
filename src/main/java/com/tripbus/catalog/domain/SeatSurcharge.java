package com.tripbus.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/** US-141: recargo en COP de una empresa para una categoría de asiento. Sin fila, el recargo es 0. */
@Entity
@Table(name = "seat_category_surcharges")
public class SeatSurcharge {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CategoriaAsiento categoria;

    @Column(nullable = false)
    private BigDecimal recargo;

    protected SeatSurcharge() {
    }

    public SeatSurcharge(UUID id, UUID companyId, CategoriaAsiento categoria, BigDecimal recargo) {
        this.id = id;
        this.companyId = companyId;
        this.categoria = categoria;
        this.recargo = recargo;
    }

    public void cambiarRecargo(BigDecimal recargo) {
        this.recargo = recargo;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public CategoriaAsiento getCategoria() {
        return categoria;
    }

    public BigDecimal getRecargo() {
        return recargo;
    }
}
