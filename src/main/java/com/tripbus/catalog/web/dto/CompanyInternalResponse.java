package com.tripbus.catalog.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * GET /internal/companies/{id}: lo que identity necesita para validar el companyId de un operador. Sprint 6: nit
 * (booking lo pone en el comprobante de pago).
 */
public record CompanyInternalResponse(UUID id, String nombre, String nit, String estado,
                                      BigDecimal comisionPorcentaje, BigDecimal cuotaMensual) {
}
