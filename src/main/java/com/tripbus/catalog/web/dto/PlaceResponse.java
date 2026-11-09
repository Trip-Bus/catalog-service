package com.tripbus.catalog.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * US-115: sugerencia del autocompletado. tipo CIUDAD (nombre = ciudad) o TERMINAL (con su ciudad y terminalId).
 */
public record PlaceResponse(String tipo, String nombre, String ciudad,
                            @JsonInclude(JsonInclude.Include.NON_NULL) UUID terminalId) {

    public static final String CIUDAD = "CIUDAD";
    public static final String TERMINAL = "TERMINAL";
}
