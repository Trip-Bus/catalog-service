package com.tripbus.catalog.web.dto;

import com.tripbus.catalog.domain.DiaSemana;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

/**
 * US-116: programación recurrente. Un viaje por cada fecha de [desde, hasta] cuyo día esté en diasSemana,
 * a la horaSalida ("06:00") en hora de Colombia.
 */
public record ScheduleRequest(
        @NotNull(message = "routeId es obligatorio") UUID routeId,
        @NotNull(message = "busId es obligatorio") UUID busId,
        @NotNull(message = "horaSalida es obligatoria (HH:mm)") LocalTime horaSalida,
        @NotEmpty(message = "diasSemana debe tener al menos un día (LUNES..DOMINGO)") Set<DiaSemana> diasSemana,
        @NotNull(message = "desde es obligatorio") LocalDate desde,
        @NotNull(message = "hasta es obligatorio") LocalDate hasta,
        @NotNull(message = "precioBase es obligatorio") BigDecimal precioBase) {
}
