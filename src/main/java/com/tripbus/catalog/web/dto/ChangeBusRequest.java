package com.tripbus.catalog.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** US-119: bus nuevo del viaje (de la misma empresa y ACTIVO). */
public record ChangeBusRequest(
        @NotNull(message = "busId es obligatorio") UUID busId,
        @NotBlank(message = "El motivo es obligatorio")
        @Size(max = 300, message = "El motivo no puede superar 300 caracteres")
        String motivo) {
}
