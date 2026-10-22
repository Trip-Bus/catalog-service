package com.tripbus.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Distribución física del bus (filas, columnas, pasillo, baño, numeración) en JSON. */
@Entity
@Table(name = "seat_maps")
public class SeatMap {

    @Id
    private UUID id;

    @Column(name = "bus_id", nullable = false, unique = true)
    private UUID busId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String distribucion;

    protected SeatMap() {
    }

    public SeatMap(UUID id, UUID busId, String distribucion) {
        this.id = id;
        this.busId = busId;
        this.distribucion = distribucion;
    }

    public void cambiarDistribucion(String distribucion) {
        this.distribucion = distribucion;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusId() {
        return busId;
    }

    public String getDistribucion() {
        return distribucion;
    }
}
