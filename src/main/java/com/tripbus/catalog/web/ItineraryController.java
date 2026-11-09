package com.tripbus.catalog.web;

import com.tripbus.catalog.service.ItineraryService;
import com.tripbus.catalog.service.PricingService;
import com.tripbus.catalog.web.dto.ItineraryDetail;
import com.tripbus.catalog.web.dto.PriceResponse;
import com.tripbus.catalog.web.dto.SeatMapResponse;
import com.tripbus.catalog.web.dto.StopResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ItineraryController {

    private final ItineraryService itineraryService;
    private final PricingService pricingService;

    public ItineraryController(ItineraryService itineraryService, PricingService pricingService) {
        this.itineraryService = itineraryService;
        this.pricingService = pricingService;
    }

    @GetMapping("/itineraries/{id}")
    public ItineraryDetail detail(@PathVariable UUID id) {
        return itineraryService.detail(id);
    }

    @GetMapping("/itineraries/{id}/seatmap")
    public SeatMapResponse seatMap(@PathVariable UUID id) {
        return itineraryService.seatMap(id);
    }

    @Operation(summary = "Precio del viaje (público)",
            description = "Sin parámetros, el precio base y la oferta activa. US-138: con from y to (orden de las "
                    + "paradas) y/o tipos (ADULTO,NINO,ADULTO_MAYOR) agrega desglose por pasajero: tramo, temporada y "
                    + "descuento. 400 INVALID_PRICE_REQUEST.")
    @GetMapping("/itineraries/{id}/price")
    public PriceResponse price(@PathVariable UUID id,
                               @RequestParam(required = false) Integer from,
                               @RequestParam(required = false) Integer to,
                               @RequestParam(required = false) String tipos) {
        PriceResponse precio = itineraryService.price(id);
        if (from == null && to == null && tipos == null) {
            return precio;
        }
        return precio.conDesglose(pricingService.cotizar(id, from, to, tipos));
    }

    @GetMapping("/routes/{id}/stops")
    public List<StopResponse> stops(@PathVariable UUID id) {
        return itineraryService.stops(id);
    }
}
