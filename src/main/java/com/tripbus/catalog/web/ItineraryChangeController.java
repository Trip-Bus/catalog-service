package com.tripbus.catalog.web;

import com.tripbus.catalog.common.security.CurrentUser;
import com.tripbus.catalog.service.ItineraryChangeService;
import com.tripbus.catalog.web.dto.ChangeBusRequest;
import com.tripbus.catalog.web.dto.ItineraryChangeResponse;
import com.tripbus.catalog.web.dto.ItinerarySummary;
import com.tripbus.catalog.web.dto.RescheduleRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** US-118/US-119: reprogramar o retrasar un viaje, cambiarle el bus y ver su historial (ADMIN/OPERADOR). */
@RestController
@RequestMapping("/itineraries")
public class ItineraryChangeController {

    private final ItineraryChangeService changeService;

    public ItineraryChangeController(ItineraryChangeService changeService) {
        this.changeService = changeService;
    }

    @Operation(summary = "Reprogramar o retrasar (ADMIN/OPERADOR)",
            description = "Solo antes de la salida original y si no está cancelado; la nueva salida en el futuro. "
                    + "RETRASO si se corre hasta tripbus.catalog.max-delay (6 h), si no REPROGRAMACION. 409 "
                    + "BUS_SCHEDULE_CONFLICT si el bus queda cruzado. Publica ItineraryRescheduled.")
    @PatchMapping("/{id}/departure")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public ItinerarySummary reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleRequest request,
                                       Authentication auth) {
        return changeService.reschedule(id, request, CurrentUser.companyScope(auth), CurrentUser.id(auth));
    }

    @Operation(summary = "Cambiar el bus (ADMIN/OPERADOR)",
            description = "Bus de la misma empresa, ACTIVO y sin cruces. Seat Inventory reasigna los asientos antes "
                    + "de guardar: 409 SEATS_CONFLICT (details.asientos) o 503 SEAT_INVENTORY_UNAVAILABLE sin cambiar "
                    + "nada. Publica ItineraryBusChanged.")
    @PatchMapping("/{id}/bus")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public ItinerarySummary changeBus(@PathVariable UUID id, @Valid @RequestBody ChangeBusRequest request,
                                      Authentication auth) {
        return changeService.changeBus(id, request, CurrentUser.companyScope(auth), CurrentUser.id(auth));
    }

    @Operation(summary = "Historial de cambios del viaje (ADMIN/OPERADOR)")
    @GetMapping("/{id}/changes")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public List<ItineraryChangeResponse> changes(@PathVariable UUID id, Authentication auth) {
        return changeService.changes(id, CurrentUser.companyScope(auth));
    }
}
