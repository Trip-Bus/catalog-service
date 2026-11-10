package com.tripbus.catalog.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** US-118: nueva hora de salida. El tipo (RETRASO o REPROGRAMACION) lo calcula el servicio. */
public record RescheduleRequest(
        @NotNull(message = "nuevaFechaSalida es obligatoria") Instant nuevaFechaSalida,
        @NotBlank(message = "El motivo es obligatorio")
        @Size(max = 300, message = "El motivo no puede superar 300 caracteres")
        String motivo) {
}
