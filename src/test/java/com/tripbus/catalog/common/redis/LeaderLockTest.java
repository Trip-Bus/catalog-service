package com.tripbus.catalog.common.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Solo la réplica que obtiene la llave ejecuta el job; un fallo de Redis o del job no se propaga. */
class LeaderLockTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final LeaderLock lock = new LeaderLock(redis);
    private final AtomicInteger ejecuciones = new AtomicInteger();

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void conLaLlaveEjecutaElJob() {
        when(ops.setIfAbsent(eq("job-lock:seed"), anyString(), any(Duration.class))).thenReturn(true);
        assertThat(lock.runIfLeader("seed", Duration.ofSeconds(30), ejecuciones::incrementAndGet)).isTrue();
        assertThat(ejecuciones).hasValue(1);
    }

    @Test
    void sinLaLlaveNoEjecuta() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThat(lock.runIfLeader("seed", Duration.ofSeconds(30), ejecuciones::incrementAndGet)).isFalse();
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);
        assertThat(lock.runIfLeader("seed", Duration.ofSeconds(30), ejecuciones::incrementAndGet)).isFalse();
        assertThat(ejecuciones).hasValue(0);
    }

    @Test
    void redisCaidoNoEjecutaYNoFalla() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("caído"));
        assertThat(lock.runIfLeader("seed", Duration.ofSeconds(30), ejecuciones::incrementAndGet)).isFalse();
    }

    @Test
    void unErrorDelJobSeRegistraPeroCuentaComoEjecutado() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        assertThat(lock.runIfLeader("seed", Duration.ofSeconds(30), () -> {
            throw new IllegalStateException("falló");
        })).isTrue();
    }
}
