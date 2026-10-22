package com.tripbus.catalog.web;

import com.tripbus.catalog.common.security.CurrentUser;
import com.tripbus.catalog.domain.ItineraryStatus;
import com.tripbus.catalog.service.ItineraryPlanningService;
import com.tripbus.catalog.web.dto.CreateItineraryRequest;
import com.tripbus.catalog.web.dto.ItinerarySummary;
import com.tripbus.catalog.web.dto.PageResponse;
import com.tripbus.catalog.web.dto.ScheduleRequest;
import com.tripbus.catalog.web.dto.ScheduleResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * US-116/US-117: programar viajes (ADMIN/OPERADOR). Las consultas públicas de un viaje siguen en
 * ItineraryController.
 */
@RestController
@RequestMapping("/itineraries")
public class ItineraryPlanningController {

    private final ItineraryPlanningService planningService;

    public ItineraryPlanningController(ItineraryPlanningService planningService) {
        this.planningService = planningService;
    }

    @Operation(summary = "Programar un viaje (ADMIN/OPERADOR)",
            description = "Llegada = salida + duración de la ruta. Ruta ACTIVA, bus ACTIVO, empresa ACTIVA, salida "
                    + "futura y precioBase > 0. 409 BUS_SCHEDULE_CONFLICT (details.itineraryId) si el bus ya tiene un "
                    + "viaje que se cruza (incluido el alistamiento). Publica ItineraryPublished.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public ItinerarySummary create(@Valid @RequestBody CreateItineraryRequest request, Authentication auth) {
        return planningService.create(request, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Programación recurrente (ADMIN/OPERADOR)",
            description = "Un viaje por cada día de [desde, hasta] que esté en diasSemana, a horaSalida (hora de "
                    + "Colombia). Máximo 90 días y 200 viajes. Responde creados y omitidos con su motivo.")
    @PostMapping("/schedule")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public ScheduleResponse schedule(@Valid @RequestBody ScheduleRequest request, Authentication auth) {
        return planningService.schedule(request, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Viajes de la empresa (ADMIN/OPERADOR)",
            description = "Paginado y ordenado por salida. desde/hasta son fechas (hora de Colombia, inclusivas); "
                    + "companyId solo aplica al ADMIN.")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public PageResponse<ItinerarySummary> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) UUID routeId,
            @RequestParam(required = false) UUID busId,
            @RequestParam(required = false) ItineraryStatus estado,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication auth) {
        return planningService.list(desde, hasta, routeId, busId, estado, companyId, page, size,
                CurrentUser.companyScope(auth));
    }
}
