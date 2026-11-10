package com.tripbus.catalog.web.dto;

import com.tripbus.catalog.domain.BusStatus;
import jakarta.validation.constraints.NotNull;

public record BusStatusRequest(
        @NotNull(message = "El estado es obligatorio (ACTIVO, INACTIVO o MANTENIMIENTO)")
        BusStatus estado) {
}
