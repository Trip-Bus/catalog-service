package com.tripbus.catalog.web.dto;

import java.util.UUID;

/** GET /internal/companies/{id}: lo que identity necesita para validar el companyId de un operador. */
import java.math.BigDecimal;
public record CompanyInternalResponse(UUID id, String nombre, String estado,
                                      BigDecimal comisionPorcentaje, BigDecimal cuotaMensual) {
}
