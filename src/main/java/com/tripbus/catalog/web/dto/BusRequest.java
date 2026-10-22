package com.tripbus.catalog.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripbus.catalog.domain.BusType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * US-110: crear un bus. companyId solo lo envía el ADMIN (el OPERADOR usa la de su token).
 * distribucion es opcional (mismo formato que SeatLayoutFactory); sin ella se usa el layout por defecto del tipo.
 */
public record BusRequest(
        @NotBlank(message = "La placa es obligatoria")
        @Pattern(regexp = BusRequest.PLACA_REGEX, message = "La placa debe tener 3 letras y 3 números (ABC123)")
        String placa,
        @NotNull(message = "El tipo es obligatorio (UN_PISO o DOS_PISOS)")
        BusType tipo,
        UUID companyId,
        JsonNode distribucion) {

    public static final String PLACA_REGEX = "^[A-Za-z]{3}[0-9]{3}$";
}
