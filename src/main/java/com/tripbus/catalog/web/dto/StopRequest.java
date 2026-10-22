package com.tripbus.catalog.web.dto;

/**
 * Parada de una ruta (US-114). Sin anotaciones de validación: RouteValidator revisa todo junto y responde
 * 400 INVALID_ROUTE con el detalle de cada problema.
 */
public record StopRequest(Integer orden, String nombre, Double lat, Double lng, Integer minutosDesdeSalida) {
}
