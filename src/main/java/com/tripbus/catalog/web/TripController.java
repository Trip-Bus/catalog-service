package com.tripbus.catalog.web;

import com.tripbus.catalog.service.InProgressService;
import com.tripbus.catalog.service.SearchService;
import com.tripbus.catalog.web.dto.TripInProgress;
import com.tripbus.catalog.web.dto.TripSearchResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
    private final InProgressService inProgressService;

    public TripController(SearchService searchService, InProgressService inProgressService) {
        this.searchService = searchService;
        this.inProgressService = inProgressService;
    }

    /** US-007/US-008/US-014: GET /trips/search?origin=Bogotá&destination=Medellín&date=2026-10-02 */
    @GetMapping("/search")
    public List<TripSearchResult> search(
            @RequestParam String origin,
            @RequestParam String destination,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return searchService.search(origin, destination, date);
    }

    /**
     * US-039/US-047: GET /trips/in-progress?origin=Bogotá (público). Viajes en ruta para abrir su
     * seguimiento sin tiquete; al menos uno de origin/destination. Sin precio, asientos ni oferta.
     */
    @Operation(summary = "Viajes en ruta (público)",
            description = "Itinerarios no cancelados con fechaSalida - 30 min <= ahora <= fechaLlegadaEstimada + 30 min "
                    + "(tripbus.catalog.in-progress-before/-after), ordenados por salida, máximo 50. Para abrir el "
                    + "seguimiento del bus sin tiquete. Sin precio, asientos ni oferta; sin cache. "
                    + "400 INVALID_SEARCH si faltan origin y destination.")
    @GetMapping("/in-progress")
    public List<TripInProgress> inProgress(
            @Parameter(description = "Ciudad de origen (sin distinguir tildes ni mayúsculas)")
            @RequestParam(required = false) String origin,
            @Parameter(description = "Ciudad de destino (sin distinguir tildes ni mayúsculas)")
            @RequestParam(required = false) String destination) {
        return inProgressService.inProgress(origin, destination);
    }

    /** Ciudades disponibles (para autocompletar en el frontend). */
    @GetMapping("/cities")
    public List<String> cities() {
        return searchService.cities();
    }
}
