package com.tripbus.catalog.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Consumidor del stream de eventos contra Redis 7 real (Testcontainers): consumer group, ack, idempotencia
 * por eventId, reintentos y recuperación si el grupo desaparece.
 */
@Testcontainers
class EventConsumerIT {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate template;
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final Set<String> procesados = ConcurrentHashMap.newKeySet();
    private final List<EventEnvelope> recibidos = new CopyOnWriteArrayList<>();
    private final AtomicInteger fallas = new AtomicInteger();
    private EventConsumer consumer;
    private String stream;

    /** Atiende "Prueba" (siempre bien) y "Falla" (siempre lanza excepción). */
    private final DomainEventHandler handler = new DomainEventHandler() {
        @Override
        public Set<String> eventTypes() {
            return Set.of("Prueba", "Falla");
        }

        @Override
        public void handle(EventEnvelope event) {
            if (event.type().equals("Falla")) {
                fallas.incrementAndGet();
                throw new IllegalStateException("falla a propósito");
            }
            recibidos.add(event);
        }
    };

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        template = new StringRedisTemplate(factory);
        stream = "tripbus:events:it:" + UUID.randomUUID();
        when(processed.existsById(anyString())).thenAnswer(inv -> procesados.contains(inv.<String>getArgument(0)));
        when(processed.save(any())).thenAnswer(inv -> {
            procesados.add(inv.<ProcessedEvent>getArgument(0).getEventId());
            return inv.getArgument(0);
        });
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.stop();
        }
        factory.destroy();
    }

    @Test
    void deshabilitadoNoConsume() {
        consumer = consumidor(new EventProperties(false, stream, 0, "g", true), List.of(handler));
        consumer.start();
        assertThat(consumer.isRunning()).isFalse();
        consumidor(new EventProperties(true, stream, 0, "g", false), List.of(handler)).start();
        EventConsumer sinHandlers = consumidor(new EventProperties(true, stream, 0, "g", true), List.of());
        sinHandlers.start();
        assertThat(sinHandlers.isRunning()).isFalse();
        sinHandlers.stop();
    }

    @Test
    void procesaCadaEventoUnaSolaVezYConfirma() {
        consumer = iniciar();
        assertThat(consumer.isRunning()).isTrue();

        publicar("e1", "Prueba", "{\"offerId\":\"x\"}", true);
        await().atMost(Duration.ofSeconds(10)).until(() -> recibidos.size() == 1);
        assertThat(recibidos.get(0).eventId()).isEqualTo("e1");
        assertThat(recibidos.get(0).payload().get("offerId").asText()).isEqualTo("x");
        assertThat(recibidos.get(0).source()).isEqualTo("test");

        // Mismo eventId: idempotente. Tipo sin manejador: solo se confirma. Sin occurredAt: usa "ahora".
        publicar("e1", "Prueba", "{}", true);
        publicar("e2", "Desconocido", "{}", true);
        publicar("e3", "Prueba", "{}", false);
        await().atMost(Duration.ofSeconds(10)).until(() -> recibidos.size() == 2 && pendientes() == 0);
        assertThat(recibidos).extracting(EventEnvelope::eventId).containsExactly("e1", "e3");
        assertThat(recibidos.get(1).occurredAt()).isNotNull();
    }

    @Test
    void unManejadorQueFallaSeReintentaTresVecesYSeDescarta() {
        consumer = iniciar();
        publicar("f1", "Falla", "{}", true);
        await().atMost(Duration.ofSeconds(10)).until(() -> fallas.get() == 3 && pendientes() == 0);
        assertThat(procesados).doesNotContain("f1");
    }

    @Test
    void siBorranElGrupoLoVuelveACrearYSigueConsumiendo() {
        consumer = iniciar();
        publicar("a1", "Prueba", "{}", true);
        await().atMost(Duration.ofSeconds(10)).until(() -> recibidos.size() == 1);

        template.opsForStream().destroyGroup(stream, "catalog-it");
        await().atMost(Duration.ofSeconds(15)).until(this::grupoExiste);
        publicar("a2", "Prueba", "{}", true);
        await().atMost(Duration.ofSeconds(15)).until(() -> recibidos.size() == 2);
    }

    @Test
    void unEventoMalFormadoNoLlegaAlManejadorYElConsumidorSigue() {
        consumer = iniciar();
        publicar("m1", "Prueba", "{no es json", true);
        publicar("m2", "Prueba", "{}", true);
        await().atMost(Duration.ofSeconds(15)).until(() -> recibidos.size() == 1);
        assertThat(recibidos.get(0).eventId()).isEqualTo("m2");
    }

    @Test
    void siLaLlaveNoEsUnStreamSoloRegistraElError() {
        template.opsForValue().set(stream, "no soy un stream");
        consumer = iniciar();
        assertThat(consumer.isRunning()).isTrue();
        consumer.stop();
        assertThat(consumer.isRunning()).isFalse();
    }

    private EventConsumer iniciar() {
        EventConsumer c = consumidor(new EventProperties(true, stream, 0, "catalog-it", true), List.of(handler));
        c.start();
        return c;
    }

    private EventConsumer consumidor(EventProperties props, List<DomainEventHandler> handlers) {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        return new EventConsumer(factory, template, new ObjectMapper().findAndRegisterModules(), props,
                new TransactionTemplate(tx), processed, handlers);
    }

    private void publicar(String eventId, String tipo, String payload, boolean conFecha) {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("eventId", eventId);
        campos.put("type", tipo);
        campos.put("source", "test");
        if (conFecha) {
            campos.put("occurredAt", "2026-10-22T12:00:00Z");
        }
        campos.put("payload", payload);
        template.opsForStream().add(StreamRecords.newRecord().in(stream).ofMap(campos));
    }

    private long pendientes() {
        try {
            return template.opsForStream().pending(stream, "catalog-it").getTotalPendingMessages();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private boolean grupoExiste() {
        try {
            return template.opsForStream().groups(stream).stream().anyMatch(g -> g.groupName().equals("catalog-it"));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
