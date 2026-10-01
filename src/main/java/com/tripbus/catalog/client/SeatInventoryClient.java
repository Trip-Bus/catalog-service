package com.tripbus.catalog.client;

import com.tripbus.catalog.config.ServiceUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Disponibilidad de asientos: Catalog NO la guarda, la consulta a Seat Inventory (regla del doc).
 * Timeout corto: si Seat Inventory no responde, la búsqueda sigue funcionando sin ese dato.
 */
@Component
public class SeatInventoryClient {

    private static final Logger log = LoggerFactory.getLogger(SeatInventoryClient.class);

    private final RestClient restClient;

    public SeatInventoryClient(ServiceUrls urls) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(300);
        factory.setReadTimeout(500);
        this.restClient = RestClient.builder()
                .baseUrl(urls.seatInventoryUrl())
                .requestFactory(factory)
                .build();
    }

    public record Availability(UUID itineraryId, int disponibles, int total) {
    }

    /** Devuelve itineraryId -> asientos disponibles. Mapa vacío si Seat Inventory falla. */
    public Map<UUID, Integer> availability(Collection<UUID> itineraryIds) {
        if (itineraryIds.isEmpty()) {
            return Map.of();
        }
        String ids = itineraryIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        try {
            List<Availability> result = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/seats/availability").queryParam("itineraryIds", ids).build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            Map<UUID, Integer> map = new HashMap<>();
            if (result != null) {
                result.forEach(a -> map.put(a.itineraryId(), a.disponibles()));
            }
            return map;
        } catch (RuntimeException e) {
            log.warn("Seat Inventory no respondió la disponibilidad: {}", e.getMessage());
            return Map.of();
        }
    }
}
