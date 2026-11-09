package com.tripbus.catalog.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Parada de una ruta. horaProgramada solo se llena cuando se consulta en el contexto de un itinerario.
 * US-115: terminalId solo si la parada es una terminal.
 */
public record StopResponse(
        UUID id,
        int orden,
        String nombre,
        double lat,
        double lng,
        int minutosDesdeSalida,
        Instant horaProgramada,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID terminalId) {
}
