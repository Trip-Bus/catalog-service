package com.tripbus.catalog.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripbus.catalog.common.security.CurrentUser;
import com.tripbus.catalog.domain.BusStatus;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.service.BusService;
import com.tripbus.catalog.web.dto.BusRequest;
import com.tripbus.catalog.web.dto.BusResponse;
import com.tripbus.catalog.web.dto.BusStatusRequest;
import com.tripbus.catalog.web.dto.BusUpdateRequest;
import com.tripbus.catalog.web.dto.PageResponse;
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

import java.util.UUID;

/**
 * US-110/US-111: buses y mapa de asientos. El OPERADOR solo ve y edita los de su empresa (claim
 * companyId del JWT; un bus de otra empresa es 404). El ADMIN ve todos y envía companyId al crear.
 */
@RestController
@RequestMapping("/buses")
public class BusController {

    private final BusService busService;

    public BusController(BusService busService) {
        this.busService = busService;
    }

    @Operation(summary = "Crear bus (ADMIN/OPERADOR)",
            description = "Placa ABC123 única; tipo UN_PISO o DOS_PISOS. Sin distribucion usa el mapa por defecto del "
                    + "tipo. La capacidad es el número de asientos del mapa. 409 COMPANY_INACTIVE / PLACA_ALREADY_EXISTS.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public BusResponse create(@Valid @RequestBody BusRequest request, Authentication auth) {
        return busService.create(request, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Listar buses (ADMIN/OPERADOR)",
            description = "Paginado (page desde 0), filtros opcionales estado y tipo; companyId solo aplica al ADMIN.")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public PageResponse<BusResponse> list(
            @RequestParam(required = false) BusStatus estado,
            @RequestParam(required = false) BusType tipo,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication auth) {
        return busService.list(estado, tipo, companyId, page, size, CurrentUser.companyScope(auth));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public BusResponse get(@PathVariable UUID id, Authentication auth) {
        return busService.get(id, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Editar placa y tipo (ADMIN/OPERADOR)",
            description = "Cambiar el tipo reinicia el mapa al de por defecto; 409 BUS_IN_USE si tiene viajes futuros.")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public BusResponse update(@PathVariable UUID id, @Valid @RequestBody BusUpdateRequest request, Authentication auth) {
        return busService.update(id, request, CurrentUser.companyScope(auth));
    }

    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public BusResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody BusStatusRequest request,
                                    Authentication auth) {
        return busService.changeStatus(id, request.estado(), CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Mapa de asientos del bus (ADMIN/OPERADOR)",
            description = "Mismo JSON que /itineraries/{id}/seatmap en 'distribucion': tipo, capacidad, pisos[].celdas[].")
    @GetMapping("/{id}/seatmap")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public JsonNode seatMap(@PathVariable UUID id, Authentication auth) {
        return busService.seatMap(id, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Reemplazar el mapa de asientos (ADMIN/OPERADOR)",
            description = "Al menos 1 asiento, números únicos, pisos según el tipo (máx. 2); recalcula la capacidad. "
                    + "400 INVALID_SEATMAP; 409 BUS_IN_USE si el bus tiene viajes futuros no cancelados.")
    @PutMapping("/{id}/seatmap")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public JsonNode updateSeatMap(@PathVariable UUID id, @RequestBody JsonNode distribucion, Authentication auth) {
        return busService.updateSeatMap(id, distribucion, CurrentUser.companyScope(auth));
    }
}
