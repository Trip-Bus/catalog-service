package com.tripbus.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/** US-138: precio exacto del tramo desdeOrden → hastaOrden de una ruta (reemplaza el cálculo proporcional). */
@Entity
@Table(name = "segment_prices")
public class SegmentPrice {

    @Id
    private UUID id;

    @Column(name = "route_id", nullable = false)
    private UUID routeId;

    @Column(name = "desde_orden", nullable = false)
    private int desdeOrden;

    @Column(name = "hasta_orden", nullable = false)
    private int hastaOrden;

    @Column(nullable = false)
    private BigDecimal precio;

    protected SegmentPrice() {
    }

    public SegmentPrice(UUID id, UUID routeId, int desdeOrden, int hastaOrden, BigDecimal precio) {
        this.id = id;
        this.routeId = routeId;
        this.desdeOrden = desdeOrden;
        this.hastaOrden = hastaOrden;
        this.precio = precio;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRouteId() {
        return routeId;
    }

    public int getDesdeOrden() {
        return desdeOrden;
    }

    public int getHastaOrden() {
        return hastaOrden;
    }

    public BigDecimal getPrecio() {
        return precio;
    }
}
