package com.tripbus.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripbus.catalog.common.events.EventPublisher;
import com.tripbus.catalog.common.observability.CatalogMetrics;
import com.tripbus.catalog.domain.Asiento;
import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.CategoriaAsiento;
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
 * <p>seatNumbers, seats y capacidad salen del mapa de asientos REAL del bus (US-111), no del tipo de bus.
 * US-141: seats lleva la categoría de cada asiento; seatNumbers se mantiene por compatibilidad.
 */
@Component
public class ItineraryPublisher {

    static final String ESTANDAR = CategoriaAsiento.ESTANDAR.name();

    private final EventPublisher eventPublisher;
    private final SeatMapRepository seatMapRepository;
    private final ObjectMapper objectMapper;
    private final CatalogMetrics metrics;

    public ItineraryPublisher(EventPublisher eventPublisher, SeatMapRepository seatMapRepository,
                              ObjectMapper objectMapper, CatalogMetrics metrics) {
        this.eventPublisher = eventPublisher;
        this.seatMapRepository = seatMapRepository;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /** Un evento por viaje; el mapa de cada bus se lee una sola vez. HT-10: cuenta tripbus_viajes_programados_total. */
    public void publishAfterCommit(Collection<Itinerary> itinerarios) {
        metrics.viajesProgramados(itinerarios.size());
        Map<UUID, List<Asiento>> asientosPorBus = new HashMap<>();
        for (Itinerary i : itinerarios) {
            List<Asiento> asientos = asientosPorBus.computeIfAbsent(i.getBus().getId(), id -> asientos(i.getBus()));
            eventPublisher.publishAfterCommit(CatalogEvents.ITINERARY_PUBLISHED, payload(i, asientos));
        }
    }

    /** Asientos (con categoría) del mapa real del bus; sin mapa guardado, los del layout por defecto (ESTANDAR). */
    public List<Asiento> asientos(Bus bus) {
        return seatMapRepository.findByBusId(bus.getId())
                .map(s -> asientos(readJson(s.getDistribucion())))
                .orElseGet(() -> SeatLayoutFactory.seatNumbers(bus.getTipo()).stream()
                        .map(n -> new Asiento(n, ESTANDAR)).toList());
    }

    /** Asientos de una distribución en el orden del mapa (pisos, luego celdas). Sin categoría, ESTANDAR. */
    public static List<Asiento> asientos(JsonNode distribucion) {
        List<Asiento> asientos = new ArrayList<>();
        distribucion.path("pisos").forEach(piso -> piso.path("celdas").forEach(celda -> {
            if (celda.hasNonNull("asiento")) {
                asientos.add(new Asiento(celda.get("asiento").asText(), celda.path("categoria").asText(ESTANDAR)));
            }
        }));
        return asientos;
    }

    public static List<String> seatNumbers(JsonNode distribucion) {
        return numeros(asientos(distribucion));
    }

    public static List<String> numeros(List<Asiento> asientos) {
        return asientos.stream().map(Asiento::numero).toList();
    }

    /**
     * US-141: la distribución con la categoría en cada celda de asiento (los mapas guardados antes del Sprint 6
     * no la tienen: quedan en ESTANDAR). Modifica y devuelve el mismo nodo.
     */
    public static JsonNode conCategorias(JsonNode distribucion) {
        distribucion.path("pisos").forEach(piso -> piso.path("celdas").forEach(celda -> {
            if (celda.hasNonNull("asiento") && !celda.hasNonNull("categoria")) {
                ((ObjectNode) celda).put("categoria", ESTANDAR);
            }
        }));
        return distribucion;
    }

    /** Lista [{numero, categoria}] para los eventos y el remap de Seat Inventory. */
    public static List<Map<String, String>> seats(List<Asiento> asientos) {
        return asientos.stream().map(a -> {
            Map<String, String> seat = new LinkedHashMap<>();
            seat.put("numero", a.numero());
            seat.put("categoria", a.categoria());
            return seat;
        }).toList();
    }

    public static Map<String, Object> payload(Itinerary i, List<Asiento> asientos) {
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
        payload.put("capacidad", asientos.size());
        payload.put("seatNumbers", numeros(asientos));
        payload.put("seats", seats(asientos));
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
