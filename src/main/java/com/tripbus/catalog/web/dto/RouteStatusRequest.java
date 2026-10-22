package com.tripbus.catalog.web.dto;

import com.tripbus.catalog.domain.RouteStatus;
import jakarta.validation.constraints.NotNull;

public record RouteStatusRequest(
        @NotNull(message = "El estado es obligatorio (ACTIVA o INACTIVA)")
        RouteStatus estado) {
}
