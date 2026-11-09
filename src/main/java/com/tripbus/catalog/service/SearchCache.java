package com.tripbus.catalog.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.catalog.web.dto.CalendarDay;
import com.tripbus.catalog.web.dto.TripSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Cache de búsquedas frecuentes en Redis con TTL corto (30-60 s según el doc).
 * Solo se cachea la parte de catálogo; la disponibilidad se consulta en vivo.
 * Si Redis falla, la búsqueda va directo a PostgreSQL.
 *
 * <p>US-138: la llave incluye el modo (ver {@link SearchMode}); v2 porque los resultados ganaron servicios y
 * tramo. US-136: el calendario se guarda aparte, 60 s, ya con el cupo (solo si Seat Inventory respondió).
 */
@Component
public class SearchCache {

    private static final Logger log = LoggerFactory.getLogger(SearchCache.class);
    private static final String PREFIX = "catalog:search:v2:";
    private static final String PREFIX_CALENDARIO = "catalog:calendar:v1:";
    static final Duration TTL_CALENDARIO = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public SearchCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${tripbus.search.cache-ttl-seconds:45}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public Optional<List<TripSearchResult>> get(String origen, String destino, LocalDate fecha, String modo) {
        return leer(key(origen, destino, fecha, modo), new TypeReference<>() {
        });
    }

    public void put(String origen, String destino, LocalDate fecha, String modo, List<TripSearchResult> results) {
        guardar(key(origen, destino, fecha, modo), results, ttl);
    }

    public Optional<List<CalendarDay>> getCalendar(String origen, String destino, LocalDate desde, int dias) {
        return leer(keyCalendario(origen, destino, desde, dias), new TypeReference<>() {
        });
    }

    public void putCalendar(String origen, String destino, LocalDate desde, int dias, List<CalendarDay> calendario) {
        guardar(keyCalendario(origen, destino, desde, dias), calendario, TTL_CALENDARIO);
    }

    /** Invalida todas las búsquedas y calendarios cacheados (cuando cambia una oferta, un viaje o un precio). */
    public void evictAll() {
        try {
            for (String prefijo : List.of(PREFIX, PREFIX_CALENDARIO)) {
                var keys = redis.keys(prefijo + "*");
                if (keys != null && !keys.isEmpty()) {
                    redis.delete(keys);
                }
            }
        } catch (Exception e) {
            log.debug("No se pudo invalidar la cache de búsquedas: {}", e.getMessage());
        }
    }

    private <T> Optional<T> leer(String key, TypeReference<T> tipo) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, tipo));
        } catch (Exception e) {
            log.debug("Cache de búsqueda no disponible: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void guardar(String key, Object valor, Duration duracion) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(valor), duracion);
        } catch (Exception e) {
            log.debug("No se pudo guardar la búsqueda en cache: {}", e.getMessage());
        }
    }

    private static String key(String origen, String destino, LocalDate fecha, String modo) {
        return PREFIX + origen + ":" + destino + ":" + fecha + ":" + modo;
    }

    private static String keyCalendario(String origen, String destino, LocalDate desde, int dias) {
        return PREFIX_CALENDARIO + origen + ":" + destino + ":" + desde + ":" + dias;
    }
}
