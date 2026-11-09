package com.tripbus.catalog.web.dto;

import com.tripbus.catalog.domain.Terminal;

import java.util.UUID;

/** US-115: terminal de transporte. */
public record TerminalResponse(UUID id, String ciudad, String nombre, String direccion, double lat, double lng) {

    public static TerminalResponse from(Terminal t) {
        return new TerminalResponse(t.getId(), t.getCiudad(), t.getNombre(), t.getDireccion(), t.getLat(), t.getLng());
    }
}
