package com.tripbus.catalog.web;

import com.tripbus.catalog.service.ItineraryService;
import com.tripbus.catalog.web.dto.ItineraryDetail;
import com.tripbus.catalog.web.dto.PriceResponse;
import com.tripbus.catalog.web.dto.SeatMapResponse;
import com.tripbus.catalog.web.dto.StopResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ItineraryController {

    private final ItineraryService itineraryService;

    public ItineraryController(ItineraryService itineraryService) {
        this.itineraryService = itineraryService;
    }

    @GetMapping("/itineraries/{id}")
    public ItineraryDetail detail(@PathVariable UUID id) {
        return itineraryService.detail(id);
    }

    @GetMapping("/itineraries/{id}/seatmap")
    public SeatMapResponse seatMap(@PathVariable UUID id) {
        return itineraryService.seatMap(id);
    }

    @GetMapping("/itineraries/{id}/price")
    public PriceResponse price(@PathVariable UUID id) {
        return itineraryService.price(id);
    }

    @GetMapping("/routes/{id}/stops")
    public List<StopResponse> stops(@PathVariable UUID id) {
        return itineraryService.stops(id);
    }
}
