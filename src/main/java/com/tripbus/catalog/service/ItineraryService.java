package com.tripbus.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.common.events.EventPublisher;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.ItineraryStatus;
import com.tripbus.catalog.domain.SeatMap;
import com.tripbus.catalog.domain.Stop;
import com.tripbus.catalog.events.CatalogEvents;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.repository.SeatMapRepository;
import com.tripbus.catalog.repository.StopRepository;
import com.tripbus.catalog.web.dto.ItineraryDetail;
import com.tripbus.catalog.web.dto.OfertaResumen;
import com.tripbus.catalog.web.dto.PriceResponse;
import com.tripbus.catalog.web.dto.SeatMapResponse;
import com.tripbus.catalog.web.dto.StopResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ItineraryService {

    private final ItineraryRepository itineraryRepository;
    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final SeatMapRepository seatMapRepository;
    private final EventPublisher eventPublisher;
    private final SearchCache searchCache;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ItineraryService(
            ItineraryRepository itineraryRepository,
            RouteRepository routeRepository,
            StopRepository stopRepository,
            SeatMapRepository seatMapRepository,
            EventPublisher eventPublisher,
            SearchCache searchCache,
            ObjectMapper objectMapper,
            Clock clock) {
        this.itineraryRepository = itineraryRepository;
        this.routeRepository = routeRepository;
        this.stopRepository = stopRepository;
        this.seatMapRepository = seatMapRepository;
        this.eventPublisher = eventPublisher;
        this.searchCache = searchCache;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ItineraryDetail detail(UUID id) {
        Itinerary i = find(id);
        List<StopResponse> paradas = stopRepository.findByRouteIdOrderByOrdenAsc(i.getRoute().getId()).stream()
                .map(s -> toStop(s, i.getFechaSalida()))
                .toList();
        return new ItineraryDetail(
                i.getId(),
                i.getRoute().getId(),
                i.getBus().getId(),
                i.getRoute().getCompany().getId(),
                i.getRoute().getCompany().getNombre(),
                i.getBus().getPlaca(),
                i.getBus().getTipo().name(),
                i.getBus().getCapacidad(),
                i.getRoute().getOrigen(),
                i.getRoute().getDestino(),
                i.getFechaSalida(),
                i.getFechaLlegadaEstimada(),
                i.getEstado().name(),
                i.getPrecioBase(),
                i.getMoneda(),
                oferta(i),
                paradas);
    }

    /** US-016: distribución real del bus asignado al itinerario. */
    @Transactional(readOnly = true)
    public SeatMapResponse seatMap(UUID id) {
        Itinerary i = find(id);
        SeatMap seatMap = seatMapRepository.findByBusId(i.getBus().getId())
                .orElseThrow(() -> ApiException.notFound("SEAT_MAP_NOT_FOUND", "El bus no tiene mapa de asientos"));
        JsonNode distribucion = readJson(seatMap.getDistribucion());
        List<String> asientos = new ArrayList<>();
        distribucion.path("pisos").forEach(piso -> piso.path("celdas").forEach(celda -> {
            if (celda.hasNonNull("asiento")) {
                asientos.add(celda.get("asiento").asText());
            }
        }));
        return new SeatMapResponse(i.getId(), i.getBus().getId(), i.getBus().getPlaca(), i.getBus().getTipo().name(),
                i.getBus().getCapacidad(), asientos, distribucion);
    }

    @Transactional(readOnly = true)
    public PriceResponse price(UUID id) {
        Itinerary i = find(id);
        return new PriceResponse(i.getId(), i.getPrecioBase(), i.getMoneda(), oferta(i), i.getEstado().name());
    }

    @Transactional(readOnly = true)
    public List<StopResponse> stops(UUID routeId) {
        if (!routeRepository.existsById(routeId)) {
            throw ApiException.notFound("ROUTE_NOT_FOUND", "La ruta no existe");
        }
        return stopRepository.findByRouteIdOrderByOrdenAsc(routeId).stream().map(s -> toStop(s, null)).toList();
    }

    @Transactional
    public ItineraryDetail cancel(UUID id, String motivo) {
        Itinerary i = find(id);
        if (i.getEstado() == ItineraryStatus.CANCELADO) {
            throw ApiException.conflict("ITINERARY_ALREADY_CANCELLED", "El itinerario ya está cancelado");
        }
        i.cancelar();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itineraryId", i.getId());
        payload.put("motivo", motivo);
        eventPublisher.publishAfterCommit(CatalogEvents.ITINERARY_CANCELLED, payload);
        searchCache.evictAll();
        return detail(id);
    }

    private Itinerary find(UUID id) {
        return itineraryRepository.findDetailed(id)
                .orElseThrow(() -> ApiException.notFound("ITINERARY_NOT_FOUND", "El itinerario no existe"));
    }

    private OfertaResumen oferta(Itinerary i) {
        Instant now = clock.instant();
        return i.tieneOfertaActiva(now)
                ? new OfertaResumen(i.getOfertaId(), i.getPrecioOferta(), i.getOfertaFin())
                : null;
    }

    private static StopResponse toStop(Stop s, Instant salida) {
        Instant hora = salida != null ? salida.plusSeconds(s.getMinutosDesdeSalida() * 60L) : null;
        return new StopResponse(s.getId(), s.getOrden(), s.getNombre(), s.getLat(), s.getLng(),
                s.getMinutosDesdeSalida(), hora);
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Mapa de asientos corrupto", e);
        }
    }
}
