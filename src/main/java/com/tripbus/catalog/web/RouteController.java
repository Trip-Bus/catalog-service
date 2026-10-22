package com.tripbus.catalog.web;

import com.tripbus.catalog.common.security.CurrentUser;
import com.tripbus.catalog.domain.RouteStatus;
import com.tripbus.catalog.service.RouteService;
import com.tripbus.catalog.web.dto.PageResponse;
import com.tripbus.catalog.web.dto.RouteRequest;
import com.tripbus.catalog.web.dto.RouteResponse;
import com.tripbus.catalog.web.dto.RouteStatusRequest;
import com.tripbus.catalog.web.dto.StopRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * US-114: rutas y paradas. El OPERADOR gestiona las de su empresa (otra empresa es 404); el ADMIN envía
 * companyId al crear. GET /routes/{id}/stops sigue público en ItineraryController (lo usa fleet).
 */
@RestController
@RequestMapping("/routes")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @Operation(summary = "Crear ruta con paradas (ADMIN/OPERADOR)",
            description = "400 INVALID_ROUTE con details por campo; 409 COMPANY_INACTIVE. Publica RouteCreated.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public RouteResponse create(@RequestBody RouteRequest request, Authentication auth) {
        return routeService.create(request, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Listar rutas (ADMIN/OPERADOR)",
            description = "Paginado (page desde 0); filtros estado, origen y destino (sin distinguir tildes); "
                    + "companyId solo aplica al ADMIN. Sin paradas.")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public PageResponse<RouteResponse> list(
            @RequestParam(required = false) RouteStatus estado,
            @RequestParam(required = false) String origen,
            @RequestParam(required = false) String destino,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication auth) {
        return routeService.list(estado, origen, destino, companyId, page, size, CurrentUser.companyScope(auth));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public RouteResponse get(@PathVariable UUID id, Authentication auth) {
        return routeService.get(id, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Editar ruta (ADMIN/OPERADOR)",
            description = "Reemplaza origen, destino y paradas. 409 ROUTE_IN_USE si cambia y tiene viajes futuros. "
                    + "Publica RouteUpdated.")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public RouteResponse update(@PathVariable UUID id, @RequestBody RouteRequest request, Authentication auth) {
        return routeService.update(id, request, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Reemplazar paradas (ADMIN/OPERADOR)",
            description = "Lista completa; la primera debe ser el origen y la última el destino de la ruta. "
                    + "409 ROUTE_IN_USE si tiene viajes futuros. Publica RouteUpdated.")
    @PutMapping("/{id}/stops")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public RouteResponse replaceStops(@PathVariable UUID id, @RequestBody List<StopRequest> paradas,
                                      Authentication auth) {
        return routeService.replaceStops(id, paradas, CurrentUser.companyScope(auth));
    }

    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public RouteResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody RouteStatusRequest request,
                                      Authentication auth) {
        return routeService.changeStatus(id, request.estado(), CurrentUser.companyScope(auth));
    }
}
