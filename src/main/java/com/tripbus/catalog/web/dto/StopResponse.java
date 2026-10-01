package com.tripbus.catalog.web.dto;

import java.time.Instant;
import java.util.UUID;

/** Parada de una ruta. horaProgramada solo se llena cuando se consulta en el contexto de un itinerario. */
public record StopResponse(
        UUID id,
        int orden,
        String nombre,
        double lat,
        double lng,
        int minutosDesdeSalida,
        Instant horaProgramada) {
}
