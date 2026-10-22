package com.tripbus.catalog.web.dto;

import com.tripbus.catalog.domain.BusType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** US-110: editar placa y tipo de un bus (la empresa no cambia). */
public record BusUpdateRequest(
        @NotBlank(message = "La placa es obligatoria")
        @Pattern(regexp = BusRequest.PLACA_REGEX, message = "La placa debe tener 3 letras y 3 números (ABC123)")
        String placa,
        @NotNull(message = "El tipo es obligatorio (UN_PISO o DOS_PISOS)")
        BusType tipo) {
}
