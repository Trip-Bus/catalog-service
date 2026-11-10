package com.tripbus.catalog.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** US-116: viajes creados y fechas omitidas con su motivo (una fecha omitida no hace fallar las demás). */
public record ScheduleResponse(List<ItinerarySummary> creados, List<Omitido> omitidos) {

    public record Omitido(
            LocalDate fecha,
            Instant fechaSalida,
            String codigo,
            String motivo,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID conflictoCon) {
    }
}
