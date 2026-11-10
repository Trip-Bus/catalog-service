package com.tripbus.catalog.domain;

import com.tripbus.catalog.service.TextNormalizer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** US-115: terminal de transporte de una ciudad. */
@Entity
@Table(name = "terminals")
public class Terminal {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String ciudad;

    @Column(name = "ciudad_norm", nullable = false)
    private String ciudadNorm;

    @Column(nullable = false)
    private String nombre;

    @Column(name = "nombre_norm", nullable = false)
    private String nombreNorm;

    private String direccion;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    protected Terminal() {
    }

    public Terminal(UUID id, String ciudad, String nombre, String direccion, double lat, double lng) {
        this.id = id;
        asignar(ciudad, nombre, direccion, lat, lng);
    }

    public void actualizar(String ciudad, String nombre, String direccion, double lat, double lng) {
        asignar(ciudad, nombre, direccion, lat, lng);
    }

    private void asignar(String ciudad, String nombre, String direccion, double lat, double lng) {
        this.ciudad = ciudad;
        this.ciudadNorm = TextNormalizer.normalize(ciudad);
        this.nombre = nombre;
        this.nombreNorm = TextNormalizer.normalize(nombre);
        this.direccion = direccion;
        this.lat = lat;
        this.lng = lng;
    }

    public UUID getId() {
        return id;
    }

    public String getCiudad() {
        return ciudad;
    }

    public String getCiudadNorm() {
        return ciudadNorm;
    }

    public String getNombre() {
        return nombre;
    }

    public String getNombreNorm() {
        return nombreNorm;
    }

    public String getDireccion() {
        return direccion;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }
}
