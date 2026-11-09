package com.tripbus.catalog.web;

import com.tripbus.catalog.service.InternalItineraryService;
import com.tripbus.catalog.service.PricingService;
import com.tripbus.catalog.web.dto.InternalItinerary;
import com.tripbus.catalog.web.dto.PriceBreakdown;
import com.tripbus.catalog.web.dto.PricingRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Servicio a servicio (X-Internal-Token, ver InternalTokenFilter): viajes para flash-offers y booking y la
 * cotización con la que booking cobra (US-138/US-141). El API Gateway nunca enruta /internal/**.
 */
@RestController
public class InternalItineraryController {

    private final InternalItineraryService internalItineraryService;
    private final PricingService pricingService;

    public InternalItineraryController(InternalItineraryService internalItineraryService,
                                       PricingService pricingService) {
        this.internalItineraryService = internalItineraryService;
        this.pricingService = pricingService;
    }

    @GetMapping("/internal/itineraries")
    public List<InternalItinerary> itineraries(
            @RequestParam(required = false) UUID companyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) UUID routeId) {
        return internalItineraryService.list(companyId, desde, hasta, routeId);
    }

    @PostMapping("/internal/pricing")
    public PriceBreakdown pricing(@RequestBody PricingRequest request) {
        return pricingService.cotizar(request);
    }
}
