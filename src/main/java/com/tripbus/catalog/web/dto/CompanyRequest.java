package com.tripbus.catalog.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** US-100: crear o editar una empresa transportadora. */
public record CompanyRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 120, message = "El nombre no puede superar 120 caracteres")
        String nombre,
        @NotBlank(message = "El NIT es obligatorio")
        @Pattern(regexp = CompanyRequest.NIT_REGEX,
                message = "El NIT debe tener 9 o 10 dígitos y, opcionalmente, el dígito de verificación (900123456-7)")
        String nit) {

    public static final String NIT_REGEX = "^[0-9]{9,10}(-[0-9])?$";
}
