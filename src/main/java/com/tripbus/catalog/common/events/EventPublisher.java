package com.tripbus.catalog.common.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.catalog.common.observability.TracePropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publica eventos de dominio en el stream de Redis (XADD). Bus de eventos del MVP.
 * HT-10: si hay una traza en curso, el mensaje lleva su {@code traceparent} y el consumidor la continúa.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private static final int TRIM_EVERY = 500;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final EventProperties properties;
    private final String source;
    private final TracePropagation tracing;
    private final AtomicLong published = new AtomicLong();

    public EventPublisher(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            EventProperties properties,
            @Value("${spring.application.name}") String source,
            TracePropagation tracing) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.source = source;
        this.tracing = tracing;
    }

    /** Publica de inmediato. Lanza excepción si Redis no está disponible. */
    public String publish(String type, Object payload) {
        String eventId = UUID.randomUUID().toString();
        publish(eventId, type, payload);
        return eventId;
    }

    /** Publica con un eventId dado (el consumidor es idempotente por eventId). */
    public void publish(String eventId, String type, Object payload) {
        publish(eventId, type, payload, tracing.actual());
    }

    private void publish(String eventId, String type, Object payload, String traceparent) {
        if (!properties.enabled()) {
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventId", eventId);
        fields.put("type", type);
        fields.put("source", source);
        fields.put("occurredAt", Instant.now().toString());
        fields.put("payload", toJson(payload));
        if (traceparent != null) {
            fields.put(TracePropagation.CAMPO, traceparent);
        }

        MapRecord<String, String, String> record = StreamRecords.newRecord().in(properties.stream()).ofMap(fields);
        redis.opsForStream().add(record);
        if (published.incrementAndGet() % TRIM_EVERY == 0) {
            redis.opsForStream().trim(properties.stream(), properties.maxLen(), true);
        }
        log.debug("Evento publicado {} {}", type, eventId);
    }

    /** Publica sin propagar errores (para eventos cuya pérdida no rompe la consistencia). */
    public void publishSafely(String type, Object payload) {
        publishSafely(type, payload, tracing.actual());
    }

    private void publishSafely(String type, Object payload, String traceparent) {
        try {
            publish(UUID.randomUUID().toString(), type, payload, traceparent);
        } catch (RuntimeException e) {
            log.error("No se pudo publicar el evento {}: {}", type, e.getMessage());
        }
    }

    /**
     * Si hay una transacción activa, publica solo después del commit (para no anunciar
     * cambios que luego se revierten). Si no hay transacción, publica de inmediato. La traza se toma al
     * registrar el evento, dentro de la operación que lo origina.
     */
    public void publishAfterCommit(String type, Object payload) {
        String traceparent = tracing.actual();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishSafely(type, payload, traceparent);
                }
            });
        } else {
            publishSafely(type, payload, traceparent);
        }
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload de evento no serializable", e);
        }
    }
}
