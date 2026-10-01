package com.tripbus.catalog.web;

import com.tripbus.catalog.service.SearchService;
import com.tripbus.catalog.web.dto.TripSearchResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/trips")
public class TripController {

    private final SearchService searchService;

    public TripController(SearchService searchService) {
        this.searchService = searchService;
    }

    /** US-007/US-008/US-014: GET /trips/search?origin=Bogotá&destination=Medellín&date=2026-10-02 */
    @GetMapping("/search")
    public List<TripSearchResult> search(
            @RequestParam String origin,
            @RequestParam String destination,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return searchService.search(origin, destination, date);
    }

    /** Ciudades disponibles (para autocompletar en el frontend). */
    @GetMapping("/cities")
    public List<String> cities() {
        return searchService.cities();
    }
}
