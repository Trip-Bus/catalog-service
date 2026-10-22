package com.tripbus.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tripbus.catalog.web.dto.TripSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cache de búsquedas: si Redis falla, nunca rompe la búsqueda. */
class SearchCacheTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 11, 1);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final SearchCache cache = new SearchCache(redis, mapper, 45);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void guardaYLeeLosResultados() throws Exception {
        List<TripSearchResult> resultados = List.of(resultado());
        cache.put("bogota", "medellin", FECHA, resultados);
        verify(ops).set(eq("catalog:search:v1:bogota:medellin:2026-11-01"), anyString(), eq(Duration.ofSeconds(45)));

        when(ops.get("catalog:search:v1:bogota:medellin:2026-11-01")).thenReturn(mapper.writeValueAsString(resultados));
        assertThat(cache.get("bogota", "medellin", FECHA)).contains(resultados);
    }

    @Test
    void sinEntradaORedisCaidoNoHayCache() {
        assertThat(cache.get("bogota", "cali", FECHA)).isEmpty();
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("caído"));
        assertThat(cache.get("bogota", "cali", FECHA)).isEmpty();
        doThrow(new RedisConnectionFailureException("caído")).when(ops).set(anyString(), anyString(), any(Duration.class));
        cache.put("bogota", "cali", FECHA, List.of());
    }

    @Test
    void invalidaTodasLasBusquedas() {
        when(redis.keys("catalog:search:v1:*")).thenReturn(Set.of("a", "b"));
        cache.evictAll();
        verify(redis).delete(Set.of("a", "b"));
    }

    @Test
    void invalidarSinLlavesOConRedisCaidoNoFalla() {
        when(redis.keys(anyString())).thenReturn(Set.of());
        cache.evictAll();
        when(redis.keys(anyString())).thenReturn(null);
        cache.evictAll();
        verify(redis, never()).delete(any(Set.class));
        when(redis.keys(anyString())).thenThrow(new RedisConnectionFailureException("caído"));
        cache.evictAll();
    }

    private static TripSearchResult resultado() {
        Instant salida = Instant.parse("2026-11-01T13:00:00Z");
        return new TripSearchResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Expreso", "Bogotá",
                "Medellín", salida, salida.plusSeconds(3600), 60, new BigDecimal("95000"), "COP", "UN_PISO", 40,
                null, null, null, false);
    }
}
