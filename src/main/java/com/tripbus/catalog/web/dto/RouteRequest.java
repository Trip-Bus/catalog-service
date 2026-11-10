package com.tripbus.catalog.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * US-114: crear o editar una ruta con sus paradas. companyId solo lo envía el ADMIN al crear (el OPERADOR
 * usa la de su token; al editar se ignora). La duración es la de la última parada.
 */
public record RouteRequest(UUID companyId, String origen, String destino, List<StopRequest> paradas) {
}
