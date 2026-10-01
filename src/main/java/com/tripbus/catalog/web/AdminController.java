package com.tripbus.catalog.web;

import com.tripbus.catalog.seed.SeedService;
import com.tripbus.catalog.service.ItineraryService;
import com.tripbus.catalog.web.dto.CancelRequest;
import com.tripbus.catalog.web.dto.ItineraryDetail;
import com.tripbus.catalog.web.dto.SeedResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final ItineraryService itineraryService;

    public AdminController(SeedService seedService, ItineraryService itineraryService) {
        this.seedService = seedService;
        this.itineraryService = itineraryService;
    }

    /** HT-01: carga idempotente de datos de demostración. Solo administradores. */
    @PostMapping("/seed")
    @PreAuthorize("hasRole('ADMIN')")
    public SeedResult seed() {
        return seedService.seed();
    }

    @PostMapping("/itineraries/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public ItineraryDetail cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return itineraryService.cancel(id, request.motivo());
    }
}
