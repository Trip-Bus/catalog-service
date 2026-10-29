package com.tripbus.catalog.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.catalog.common.security.InternalTokenFilter;
import com.tripbus.catalog.config.RequestIdPropagationInterceptor;
import com.tripbus.catalog.config.ServiceUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Disponibilidad de asientos: Catalog NO la guarda, la consulta a Seat Inventory (regla del doc).
 * Timeout corto: si Seat Inventory no responde, la búsqueda sigue funcionando sin ese dato.
 * También reasigna los asientos de un viaje cuando cambia de bus (US-119).
 */
@Component
public class SeatInventoryClient {

    private static final Logger log = LoggerFactory.getLogger(SeatInventoryClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Campos de error.details donde Seat Inventory puede listar los asientos en conflicto. */
    private static final List<String> CAMPOS_ASIENTOS = List.of("asientos", "seatNumbers", "missingSeats", "seats");

    private final RestClient restClient;
    private final String internalToken;

    public SeatInventoryClient(ServiceUrls urls, @Value("${tripbus.internal-token}") String internalToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(300);
        factory.setReadTimeout(500);
        this.restClient = RestClient.builder()
                .baseUrl(urls.seatInventoryUrl())
                .requestFactory(factory)
                .requestInterceptor(new RequestIdPropagationInterceptor())
                .build();
        this.internalToken = internalToken;
    }

    public record Availability(UUID itineraryId, int disponibles, int total) {
    }
    public record Occupancy(int vendidos, int disponibles, int bloqueados, java.math.BigDecimal porcentaje, java.math.BigDecimal tasaAbandono) { }

    public Optional<Map<UUID, Occupancy>> occupancy(Collection<UUID> itineraryIds) {
        if (itineraryIds.isEmpty()) return Optional.of(Map.of());
        String ids = itineraryIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        try {
            JsonNode body = restClient.get().uri(uriBuilder -> uriBuilder.path("/internal/occupancy")
                    .queryParam("itineraryIds", ids).build()).header(InternalTokenFilter.HEADER, internalToken)
                    .retrieve().body(JsonNode.class);
            JsonNode items = body != null && body.isArray() ? body : body == null ? JSON.createArrayNode() : body.path("occupaciones");
            Map<UUID, Occupancy> result = new HashMap<>();
            for (JsonNode item : items) {
                UUID id = UUID.fromString(item.path("itineraryId").asText());
                result.put(id, new Occupancy(item.path("vendidos").asInt(), item.path("disponibles").asInt(),
                        item.path("bloqueados").asInt(), item.path("porcentaje").decimalValue(),
                        item.path("tasaAbandono").decimalValue()));
            }
            return Optional.of(result);
        } catch (RuntimeException e) {
            log.warn("Seat Inventory no respondió la ocupación: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Resultado de POST /internal/itineraries/{id}/remap. */
    public record RemapResult(Status status, List<String> asientosEnConflicto) {

        public enum Status { OK, SEATS_CONFLICT, UNAVAILABLE }

        static RemapResult ok() {
            return new RemapResult(Status.OK, List.of());
        }

        static RemapResult conflict(List<String> asientos) {
            return new RemapResult(Status.SEATS_CONFLICT, asientos);
        }

        static RemapResult unavailable() {
            return new RemapResult(Status.UNAVAILABLE, List.of());
        }
    }

    /**
     * Devuelve itineraryId -> asientos disponibles, u Optional vacío si Seat Inventory falla.
     * Seat Inventory omite los itinerarios que aún no conoce: nadie ha ocupado sus asientos.
     */
    public Optional<Map<UUID, Integer>> availability(Collection<UUID> itineraryIds) {
        if (itineraryIds.isEmpty()) {
            return Optional.of(Map.of());
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
            return Optional.of(map);
        } catch (RuntimeException e) {
            log.warn("Seat Inventory no respondió la disponibilidad: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * US-119: el viaje pasa a un bus con estos asientos. Solo una respuesta 2xx es éxito; un 409 trae los
     * asientos vendidos o retenidos que no existen en el bus nuevo; cualquier otra respuesta o un timeout es
     * "no disponible" (Catalog no cambia nada).
     */
    public RemapResult remap(UUID itineraryId, List<String> seatNumbers, int capacidad) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seatNumbers", seatNumbers);
        body.put("capacidad", capacidad);
        try {
            restClient.post()
                    .uri("/internal/itineraries/{id}/remap", itineraryId)
                    .header(InternalTokenFilter.HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return RemapResult.ok();
        } catch (HttpClientErrorException.Conflict e) {
            return RemapResult.conflict(asientosEnConflicto(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            log.warn("Seat Inventory no reasignó los asientos del viaje {}: {}", itineraryId, e.getMessage());
            return RemapResult.unavailable();
        }
    }

    /** Lista de asientos de {"error":{"details":{"asientos":[...]}}} (o de otro campo conocido). */
    static List<String> asientosEnConflicto(String body) {
        List<String> asientos = new ArrayList<>();
        try {
            JsonNode details = JSON.readTree(body).path("error").path("details");
            CAMPOS_ASIENTOS.stream()
                    .map(details::path)
                    .filter(JsonNode::isArray)
                    .findFirst()
                    .ifPresent(lista -> lista.forEach(a -> asientos.add(a.asText())));
        } catch (JsonProcessingException e) {
            log.warn("Respuesta 409 de Seat Inventory sin el formato esperado: {}", e.getMessage());
        }
        return asientos;
    }
}
