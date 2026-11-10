package com.tripbus.catalog.web.dto;

/**
 * US-115: crear o editar una terminal (solo ADMIN). Sin anotaciones de validación: TerminalService revisa todo
 * junto y responde 400 INVALID_TERMINAL con el detalle de cada campo.
 */
public record TerminalRequest(String ciudad, String nombre, String direccion, Double lat, Double lng) {
}
