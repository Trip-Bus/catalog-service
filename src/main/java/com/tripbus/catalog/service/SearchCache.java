package com.tripbus.catalog.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
@Component
public class SearchCache {

    private static final Logger log = LoggerFactory.getLogger(SearchCache.class);
    private static final String PREFIX = "catalog:search:v1:";

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

    public Optional<List<TripSearchResult>> get(String origen, String destino, LocalDate fecha) {
        try {
            String json = redis.opsForValue().get(key(origen, destino, fecha));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, new TypeReference<List<TripSearchResult>>() {
            }));
        } catch (Exception e) {
            log.debug("Cache de búsqueda no disponible: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String origen, String destino, LocalDate fecha, List<TripSearchResult> results) {
        try {
            redis.opsForValue().set(key(origen, destino, fecha), objectMapper.writeValueAsString(results), ttl);
        } catch (Exception e) {
            log.debug("No se pudo guardar la búsqueda en cache: {}", e.getMessage());
        }
    }

    /** Invalida todas las búsquedas cacheadas (cuando cambia una oferta o se cancela un viaje). */
    public void evictAll() {
        try {
            var keys = redis.keys(PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            log.debug("No se pudo invalidar la cache de búsquedas: {}", e.getMessage());
        }
    }

    private static String key(String origen, String destino, LocalDate fecha) {
        return PREFIX + origen + ":" + destino + ":" + fecha;
    }
}
