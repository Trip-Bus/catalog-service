package com.tripbus.catalog.domain;

import com.tripbus.catalog.service.TextNormalizer;

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

    /** US-138: nombre en minúsculas y sin tildes, para encontrar tramos intermedios en la búsqueda. */
    @Column(name = "nombre_norm", nullable = false)
    private String nombreNorm;

    /** US-115: terminal de transporte de la parada (opcional). */
    @Column(name = "terminal_id")
    private UUID terminalId;

    protected Stop() {
    }

    public Stop(UUID id, UUID routeId, int orden, String nombre, double lat, double lng, int minutosDesdeSalida) {
        this(id, routeId, orden, nombre, lat, lng, minutosDesdeSalida, null);
    }

    public Stop(UUID id, UUID routeId, int orden, String nombre, double lat, double lng, int minutosDesdeSalida,
                UUID terminalId) {
        this.id = id;
        this.routeId = routeId;
        this.orden = orden;
        this.nombre = nombre;
        this.nombreNorm = TextNormalizer.normalize(nombre);
        this.lat = lat;
        this.lng = lng;
        this.minutosDesdeSalida = minutosDesdeSalida;
        this.terminalId = terminalId;
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

    public String getNombreNorm() {
        return nombreNorm;
    }

    public UUID getTerminalId() {
        return terminalId;
    }
}
