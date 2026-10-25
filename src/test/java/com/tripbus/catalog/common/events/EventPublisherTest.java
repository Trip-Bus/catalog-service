package com.tripbus.catalog.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** XADD al stream con el sobre común; después del commit si hay transacción. */
class EventPublisherTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
    private final EventProperties props = new EventProperties(true, "tripbus:events:test", 1000, "catalog", true);
    private final EventPublisher publisher = new EventPublisher(redis, new ObjectMapper(), props, "catalog-service");

    @BeforeEach
    void setUp() {
        when(redis.opsForStream()).thenReturn(stream);
    }

    @AfterEach
    void limpiar() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void publicaElSobreConElPayloadEnJson() {
        String id = publisher.publish("CompanyCreated", Map.of("companyId", "c1"));

        ArgumentCaptor<MapRecord> record = ArgumentCaptor.forClass(MapRecord.class);
        verify(stream).add(record.capture());
        Map<String, String> campos = (Map<String, String>) record.getValue().getValue();
        assertThat(record.getValue().getStream()).isEqualTo("tripbus:events:test");
        assertThat(campos).containsEntry("eventId", id).containsEntry("type", "CompanyCreated")
                .containsEntry("source", "catalog-service").containsEntry("payload", "{\"companyId\":\"c1\"}")
                .containsKey("occurredAt");
    }

    @Test
    void recortaElStreamCada500Eventos() {
        for (int i = 0; i < 500; i++) {
            publisher.publish("X", Map.of());
        }
        verify(stream, times(1)).trim("tripbus:events:test", 1000, true);
    }

    @Test
    void deshabilitadoNoPublica() {
        EventPublisher apagado = new EventPublisher(redis,
                new ObjectMapper(), new EventProperties(false, null, 0, "g", false), "catalog-service");
        apagado.publish("evt-1", "X", Map.of());
        assertThat(apagado.publish("X", Map.of())).isNotBlank();
        verify(redis, never()).opsForStream();
    }

    @Test
    void payloadNoSerializableEsError() {
        assertThatThrownBy(() -> publisher.publish("X", new Object()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishSafelyNoPropagaFallosDeRedis() {
        when(stream.add(any(MapRecord.class))).thenThrow(new RedisConnectionFailureException("caído"));
        publisher.publishSafely("X", Map.of());
        verify(stream).add(any(MapRecord.class));
    }

    @Test
    void conTransaccionPublicaSoloDespuesDelCommit() {
        TransactionSynchronizationManager.initSynchronization();
        publisher.publishAfterCommit("X", Map.of());
        verify(stream, never()).add(any(MapRecord.class));

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        verify(stream).add(any(MapRecord.class));
    }

    @Test
    void sinTransaccionPublicaDeInmediato() {
        publisher.publishAfterCommit("X", Map.of());
        verify(stream).add(any(MapRecord.class));
        verify(stream, never()).trim(anyString(), anyLong(), eq(true));
    }

    @Test
    void propiedadesPorDefecto() {
        EventProperties p = new EventProperties(true, " ", -1, "g", true);
        assertThat(p.stream()).isEqualTo("tripbus:events");
        assertThat(p.maxLen()).isEqualTo(100_000);
        assertThat(new EventProperties(true, null, 5, "g", true).stream()).isEqualTo("tripbus:events");
        assertThat(props.maxLen()).isEqualTo(1000);
    }
}
