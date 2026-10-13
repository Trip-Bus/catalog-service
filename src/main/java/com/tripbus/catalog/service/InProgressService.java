package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.web.dto.TripInProgress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * US-039/US-047: viajes en ruta, para que cualquiera (con o sin tiquete) abra el seguimiento del bus.
 * Misma ventana que el seguimiento de fleet: de fechaSalida - before a fechaLlegadaEstimada + after.
 * Sin cache: la ventana se mueve con el reloj.
 */
@Service
public class InProgressService {

    static final int MAX_RESULTADOS = 50;

    private final ItineraryRepository itineraryRepository;
    private final Clock clock;
    private final Duration before;
    private final Duration after;

    public InProgressService(
            ItineraryRepository itineraryRepository,
            Clock clock,
            @Value("${tripbus.catalog.in-progress-before:30m}") Duration before,
            @Value("${tripbus.catalog.in-progress-after:30m}") Duration after) {
        this.itineraryRepository = itineraryRepository;
        this.clock = clock;
        this.before = before;
        this.after = after;
    }

    @Transactional(readOnly = true)
    public List<TripInProgress> inProgress(String origin, String destination) {
        String origen = TextNormalizer.normalize(origin);
        String destino = TextNormalizer.normalize(destination);
        if (origen.isEmpty() && destino.isEmpty()) {
            throw ApiException.badRequest("INVALID_SEARCH", "Indica al menos el origen o el destino");
        }
        Instant now = clock.instant();
        // fechaSalida - before <= now  <=>  fechaSalida <= now + before (igual con la llegada).
        return itineraryRepository.findInProgress(origen, destino, now.plus(before), now.minus(after),
                        Limit.of(MAX_RESULTADOS)).stream()
                .map(InProgressService::toResult)
                .toList();
    }

    static TripInProgress toResult(Itinerary i) {
        return new TripInProgress(
                i.getId(),
                i.getBus().getId(),
                i.getRoute().getCompany().getNombre(),
                i.getRoute().getOrigen(),
                i.getRoute().getDestino(),
                i.getFechaSalida(),
                i.getFechaLlegadaEstimada());
    }
}
