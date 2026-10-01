package com.tripbus.catalog.service;

import com.tripbus.catalog.client.SeatInventoryClient;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.web.dto.OfertaResumen;
import com.tripbus.catalog.web.dto.TripSearchResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Service
public class SearchService {

    private final ItineraryRepository itineraryRepository;
    private final RouteRepository routeRepository;
    private final SeatInventoryClient seatInventoryClient;
    private final SearchCache cache;
    private final Clock clock;

    public SearchService(
            ItineraryRepository itineraryRepository,
            RouteRepository routeRepository,
            SeatInventoryClient seatInventoryClient,
            SearchCache cache,
            Clock clock) {
        this.itineraryRepository = itineraryRepository;
        this.routeRepository = routeRepository;
        this.seatInventoryClient = seatInventoryClient;
        this.cache = cache;
        this.clock = clock;
    }

    /** US-007 + US-008 + US-014. Resultados ordenados por hora de salida. */
    @Transactional(readOnly = true)
    public List<TripSearchResult> search(String origin, String destination, LocalDate date) {
        String origen = TextNormalizer.normalize(origin);
        String destino = TextNormalizer.normalize(destination);
        if (origen.isEmpty() || destino.isEmpty()) {
            throw ApiException.badRequest("INVALID_SEARCH", "Origen y destino son obligatorios");
        }
        if (origen.equals(destino)) {
            throw ApiException.badRequest("INVALID_SEARCH", "El origen y el destino deben ser diferentes");
        }

        List<TripSearchResult> base = cache.get(origen, destino, date).orElseGet(() -> {
            List<TripSearchResult> fromDb = queryDatabase(origen, destino, date);
            cache.put(origen, destino, date, fromDb);
            return fromDb;
        });

        // Los viajes que ya salieron no se ofrecen (relevante si el resultado venía de cache).
        Instant now = clock.instant();
        List<TripSearchResult> vigentes = base.stream().filter(r -> r.fechaSalida().isAfter(now)).toList();

        Map<java.util.UUID, Integer> disponibilidad =
                seatInventoryClient.availability(vigentes.stream().map(TripSearchResult::itineraryId).toList());
        return vigentes.stream()
                .map(r -> r.withDisponibilidad(disponibilidad.get(r.itineraryId())).sinOfertaVencida(now))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> cities() {
        TreeSet<String> cities = new TreeSet<>(routeRepository.findOrigenes());
        cities.addAll(routeRepository.findDestinos());
        return List.copyOf(cities);
    }

    private List<TripSearchResult> queryDatabase(String origen, String destino, LocalDate date) {
        Instant dayStart = date.atStartOfDay(CatalogTime.ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(CatalogTime.ZONE).toInstant();
        Instant now = clock.instant();
        Instant desde = dayStart.isAfter(now) ? dayStart : now;
        if (!desde.isBefore(dayEnd)) {
            return List.of();
        }
        return itineraryRepository.search(origen, destino, desde, dayEnd).stream()
                .map(i -> toResult(i, now))
                .toList();
    }

    static TripSearchResult toResult(Itinerary i, Instant now) {
        OfertaResumen oferta = i.tieneOfertaActiva(now)
                ? new OfertaResumen(i.getOfertaId(), i.getPrecioOferta(), i.getOfertaFin())
                : null;
        return new TripSearchResult(
                i.getId(),
                i.getRoute().getId(),
                i.getBus().getId(),
                i.getRoute().getCompany().getNombre(),
                i.getRoute().getOrigen(),
                i.getRoute().getDestino(),
                i.getFechaSalida(),
                i.getFechaLlegadaEstimada(),
                i.getRoute().getDuracionMinutos(),
                i.getPrecioBase(),
                i.getMoneda(),
                i.getBus().getTipo().name(),
                i.getBus().getCapacidad(),
                null,
                null,
                oferta);
    }
}
