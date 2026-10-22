package com.tripbus.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.catalog.common.events.EventPublisher;
import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.events.CatalogEvents;
import com.tripbus.catalog.repository.SeatMapRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publica ItineraryPublished (después del commit) con el payload que consume Seat Inventory para crear
 * los asientos del viaje. Lo usan el seed y la programación de viajes (US-116).
 *
 * <p>seatNumbers y capacidad salen del mapa de asientos REAL del bus (US-111), no del tipo de bus.
 */
@Component
public class ItineraryPublisher {

    private final EventPublisher eventPublisher;
    private final SeatMapRepository seatMapRepository;
    private final ObjectMapper objectMapper;

    public ItineraryPublisher(EventPublisher eventPublisher, SeatMapRepository seatMapRepository,
                              ObjectMapper objectMapper) {
        this.eventPublisher = eventPublisher;
        this.seatMapRepository = seatMapRepository;
        this.objectMapper = objectMapper;
    }

    /** Un evento por viaje; el mapa de cada bus se lee una sola vez. */
    public void publishAfterCommit(Collection<Itinerary> itinerarios) {
        Map<UUID, List<String>> asientosPorBus = new HashMap<>();
        for (Itinerary i : itinerarios) {
            List<String> asientos = asientosPorBus.computeIfAbsent(i.getBus().getId(), id -> seatNumbers(i.getBus()));
            eventPublisher.publishAfterCommit(CatalogEvents.ITINERARY_PUBLISHED, payload(i, asientos));
        }
    }

    /** Números de asiento del mapa real del bus; sin mapa guardado, los del layout por defecto de su tipo. */
    public List<String> seatNumbers(Bus bus) {
        return seatMapRepository.findByBusId(bus.getId())
                .map(s -> seatNumbers(readJson(s.getDistribucion())))
                .orElseGet(() -> SeatLayoutFactory.seatNumbers(bus.getTipo()));
    }

    /** Asientos de una distribución en el orden del mapa (pisos, luego celdas). */
    public static List<String> seatNumbers(JsonNode distribucion) {
        List<String> asientos = new ArrayList<>();
        distribucion.path("pisos").forEach(piso -> piso.path("celdas").forEach(celda -> {
            if (celda.hasNonNull("asiento")) {
                asientos.add(celda.get("asiento").asText());
            }
        }));
        return asientos;
    }

    public static Map<String, Object> payload(Itinerary i, List<String> seatNumbers) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itineraryId", i.getId());
        payload.put("routeId", i.getRoute().getId());
        payload.put("busId", i.getBus().getId());
        payload.put("companyId", i.getRoute().getCompany().getId());
        payload.put("origen", i.getRoute().getOrigen());
        payload.put("destino", i.getRoute().getDestino());
        payload.put("fechaSalida", i.getFechaSalida());
        payload.put("fechaLlegada", i.getFechaLlegadaEstimada());
        payload.put("precioBase", i.getPrecioBase());
        payload.put("tipoBus", i.getBus().getTipo().name());
        payload.put("capacidad", seatNumbers.size());
        payload.put("seatNumbers", seatNumbers);
        return payload;
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Mapa de asientos corrupto", e);
        }
    }
}
