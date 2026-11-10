package com.tripbus.catalog.web.dto;

import java.util.Set;
import io.swagger.v3.oas.annotations.media.Schema;
import com.tripbus.catalog.domain.ServicioBus;
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
        BusType tipo,
        @Schema(description = "US-113: servicios a bordo; sin el campo se conservan los actuales, [] los quita")
        Set<ServicioBus> servicios) {
}
