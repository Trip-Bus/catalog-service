package com.tripbus.catalog.web.dto;

import java.time.Instant;
import java.util.UUID;

/** Un viaje en ruta (US-039/US-047): solo lo necesario para abrir su seguimiento, sin precio ni asientos. */
public record TripInProgress(
        UUID itineraryId,
        UUID busId,
        String empresa,
        String origen,
        String destino,
        Instant fechaSalida,
        Instant fechaLlegadaEstimada) {
}
