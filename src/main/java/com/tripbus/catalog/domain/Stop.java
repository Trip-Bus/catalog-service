package com.tripbus.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "stops")
public class Stop {

    @Id
    private UUID id;

    @Column(name = "route_id", nullable = false)
    private UUID routeId;

    @Column(nullable = false)
    private int orden;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Column(name = "minutos_desde_salida", nullable = false)
    private int minutosDesdeSalida;

    protected Stop() {
    }

    public Stop(UUID id, UUID routeId, int orden, String nombre, double lat, double lng, int minutosDesdeSalida) {
        this.id = id;
        this.routeId = routeId;
        this.orden = orden;
        this.nombre = nombre;
        this.lat = lat;
        this.lng = lng;
        this.minutosDesdeSalida = minutosDesdeSalida;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRouteId() {
        return routeId;
    }

    public int getOrden() {
        return orden;
    }

    public String getNombre() {
        return nombre;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public int getMinutosDesdeSalida() {
        return minutosDesdeSalida;
    }
}
