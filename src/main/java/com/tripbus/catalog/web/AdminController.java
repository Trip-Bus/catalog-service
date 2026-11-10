package com.tripbus.catalog.web;

import com.tripbus.catalog.common.security.CurrentUser;
import com.tripbus.catalog.seed.SeedService;
import com.tripbus.catalog.service.ItineraryChangeService;
import com.tripbus.catalog.web.dto.CancelRequest;
import com.tripbus.catalog.web.dto.ItineraryDetail;
import com.tripbus.catalog.web.dto.SeedResult;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final SeedService seedService;
    private final ItineraryChangeService changeService;

    public AdminController(SeedService seedService, ItineraryChangeService changeService) {
        this.seedService = seedService;
        this.changeService = changeService;
    }

    /** HT-01: carga idempotente de datos de demostración. Solo administradores. */
    @PostMapping("/seed")
    @PreAuthorize("hasRole('ADMIN')")
    public SeedResult seed() {
        return seedService.seed();
    }

    @Operation(summary = "Cancelar un viaje (ADMIN/OPERADOR)",
            description = "El OPERADOR solo cancela viajes de su empresa (otra empresa es 404). 409 "
                    + "ITINERARY_ALREADY_CANCELLED o ITINERARY_FINISHED. Publica ItineraryCancelled y guarda el historial.")
    @PostMapping("/itineraries/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public ItineraryDetail cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request,
                                  Authentication auth) {
        return changeService.cancel(id, request.motivo(), CurrentUser.companyScope(auth), CurrentUser.id(auth));
    }
}
