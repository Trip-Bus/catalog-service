package com.tripbus.catalog.web.dto;

import java.util.UUID;

/**
 * Parada de una ruta (US-114). US-115: terminalId opcional (debe existir). Sin anotaciones de validación: RouteValidator revisa todo junto y responde
 * 400 INVALID_ROUTE con el detalle de cada problema.
 */
public record StopRequest(Integer orden, String nombre, Double lat, Double lng, Integer minutosDesdeSalida,
                          UUID terminalId) {

    /** Parada sin terminal (US-115 la hace opcional). */
    public StopRequest(Integer orden, String nombre, Double lat, Double lng, Integer minutosDesdeSalida) {
        this(orden, nombre, lat, lng, minutosDesdeSalida, null);
    }
}
