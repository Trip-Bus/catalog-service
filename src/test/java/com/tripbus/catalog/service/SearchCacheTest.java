package com.tripbus.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tripbus.catalog.web.dto.CalendarDay;
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
        cache.put("bogota", "medellin", FECHA, "AUTO", resultados);
        verify(ops).set(eq("catalog:search:v2:bogota:medellin:2026-11-01:AUTO"), anyString(), eq(Duration.ofSeconds(45)));

        when(ops.get("catalog:search:v2:bogota:medellin:2026-11-01:AUTO"))
                .thenReturn(mapper.writeValueAsString(resultados));
        assertThat(cache.get("bogota", "medellin", FECHA, "AUTO")).contains(resultados);
        // Otro modo es otra entrada.
        assertThat(cache.get("bogota", "medellin", FECHA, "TODOS")).isEmpty();
    }

    @Test
    void guardaYLeeElCalendarioPor60Segundos() throws Exception {
        List<CalendarDay> dias = List.of(new CalendarDay(FECHA, new BigDecimal("95000"), 5, true),
                new CalendarDay(FECHA.plusDays(1), null, 0, false));
        cache.putCalendar("bogota", "medellin", FECHA, 2, dias);
        verify(ops).set(eq("catalog:calendar:v1:bogota:medellin:2026-11-01:2"), anyString(), eq(Duration.ofSeconds(60)));

        when(ops.get("catalog:calendar:v1:bogota:medellin:2026-11-01:2")).thenReturn(mapper.writeValueAsString(dias));
        assertThat(cache.getCalendar("bogota", "medellin", FECHA, 2)).contains(dias);
        assertThat(cache.getCalendar("bogota", "medellin", FECHA, 3)).isEmpty();
    }

    @Test
    void sinEntradaORedisCaidoNoHayCache() {
        assertThat(cache.get("bogota", "cali", FECHA, "AUTO")).isEmpty();
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("caído"));
        assertThat(cache.get("bogota", "cali", FECHA, "AUTO")).isEmpty();
        doThrow(new RedisConnectionFailureException("caído")).when(ops).set(anyString(), anyString(), any(Duration.class));
        cache.put("bogota", "cali", FECHA, "AUTO", List.of());
    }

    @Test
    void invalidaTodasLasBusquedas() {
        when(redis.keys("catalog:search:v2:*")).thenReturn(Set.of("a", "b"));
        when(redis.keys("catalog:calendar:v1:*")).thenReturn(Set.of("c"));
        cache.evictAll();
        verify(redis).delete(Set.of("a", "b"));
        verify(redis).delete(Set.of("c"));
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
