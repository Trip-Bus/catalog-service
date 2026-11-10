package com.tripbus.catalog.web.dto;

import com.tripbus.catalog.domain.CompanyStatus;
import jakarta.validation.constraints.NotNull;

public record CompanyStatusRequest(
        @NotNull(message = "El estado es obligatorio (ACTIVA o INACTIVA)")
        CompanyStatus estado) {
}
