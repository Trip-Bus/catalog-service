package com.tripbus.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.catalog.common.events.EventEnvelope;
import com.tripbus.catalog.events.OfferEventsHandler;
import com.tripbus.catalog.seed.SeedService;
import com.tripbus.catalog.service.CatalogTime;
import com.tripbus.catalog.web.dto.SeedResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración contra PostgreSQL 16 y Redis 7 reales (Testcontainers, requiere Docker): Flyway aplica
 * las migraciones REALES de db/migration, Hibernate valida el esquema y la cache de búsquedas usa Redis.
 * Seat Inventory apunta a un puerto cerrado: la búsqueda debe responder igual, sin disponibilidad.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestPropertySource(properties = {"tripbus.seed.on-startup=false", "SEAT_INVENTORY_URL=http://localhost:1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class CatalogIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SeedService seedService;
    @Autowired
    private OfferEventsHandler offerEventsHandler;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private ObjectMapper objectMapper;
    @BeforeAll
    void sembrar() {
        seedService.seed();
    }

    @Test
    void elSeedEsIdempotente() {
        SeedResult segunda = seedService.seed();
        assertThat(segunda.itinerariosNuevos()).isZero();
        assertThat(segunda.itinerariosExistentes()).isEqualTo(8 * 5 * 16);
    }

    @Test
    void buscarDevuelveViajesOrdenadosPorSalidaSinDistinguirTildes() throws Exception {
        LocalDate manana = LocalDate.now(CatalogTime.ZONE).plusDays(1);
        String body = mockMvc.perform(get("/trips/search")
                        .param("origin", "bogota").param("destination", "MEDELLÍN").param("date", manana.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].empresa").value("Expreso Andino"))
                .andExpect(jsonPath("$[0].capacidad").value(60))
                .andReturn().getResponse().getContentAsString();
        JsonNode results = objectMapper.readTree(body);
        for (int i = 1; i < results.size(); i++) {
            assertThat(Instant.parse(results.get(i).get("fechaSalida").asText()))
                    .isAfter(Instant.parse(results.get(i - 1).get("fechaSalida").asText()));
        }
        // Seat Inventory no está disponible en esta prueba: la búsqueda igual responde, sin ese dato.
        assertThat(results.get(0).get("asientosDisponibles").isNull()).isTrue();
        assertThat(results.get(0).get("agotado").isNull()).isTrue();
        assertThat(results.get(0).get("disponibilidadConocida").asBoolean()).isFalse();
    }

    @Test
    void busquedaConDatosDelSeedDevuelveViajesDeEseDiaOrdenadosPorSalida() throws Exception {
        LocalDate manana = LocalDate.now(CatalogTime.ZONE).plusDays(1);
        String body = mockMvc.perform(get("/trips/search")
                        .param("origin", "Bogotá").param("destination", "Medellín").param("date", manana.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode results = objectMapper.readTree(body);

        assertThat(results).isNotEmpty();
        Instant anterior = Instant.MIN;
        for (JsonNode r : results) {
            assertThat(r.get("origen").asText()).isEqualTo("Bogotá");
            assertThat(r.get("destino").asText()).isEqualTo("Medellín");
            Instant salida = Instant.parse(r.get("fechaSalida").asText());
            assertThat(ZonedDateTime.ofInstant(salida, CatalogTime.ZONE).toLocalDate()).isEqualTo(manana);
            assertThat(salida).isAfterOrEqualTo(anterior);
            anterior = salida;
        }

        // Las mismas ciudades aparecen en el autocompletado.
        mockMvc.perform(get("/trips/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@ == 'Bogotá')]").exists())
                .andExpect(jsonPath("$[?(@ == 'Medellín')]").exists());
    }

    @Test
    void mapaDeAsientosDevuelveLaDistribucionReal() throws Exception {
        UUID itineraryId = primerItinerarioDeManana();
        mockMvc.perform(get("/itineraries/{id}/seatmap", itineraryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asientos.length()").value(60))
                .andExpect(jsonPath("$.distribucion.pisos.length()").value(2));
    }

    @Test
    void ofertaActivadaMarcaElItinerarioYAlCerrarseSeDesmarca() throws Exception {
        UUID itineraryId = primerItinerarioDeManana();
        UUID offerId = UUID.randomUUID();
        var activated = objectMapper.createObjectNode()
                .put("offerId", offerId.toString())
                .put("itineraryId", itineraryId.toString())
                .put("precioOferta", 49900)
                .put("fin", Instant.now().plus(2, ChronoUnit.HOURS).toString());
        tx.executeWithoutResult(s -> offerEventsHandler.handle(
                new EventEnvelope(UUID.randomUUID().toString(), "OfferActivated", "test", Instant.now(), activated)));

        mockMvc.perform(get("/itineraries/{id}/price", itineraryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ofertaActiva.offerId").value(offerId.toString()));

        var closed = objectMapper.createObjectNode().put("offerId", offerId.toString()).put("motivo", "expirada");
        tx.executeWithoutResult(s -> offerEventsHandler.handle(
                new EventEnvelope(UUID.randomUUID().toString(), "OfferClosed", "test", Instant.now(), closed)));

        mockMvc.perform(get("/itineraries/{id}/price", itineraryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ofertaActiva").isEmpty());
    }

    @Test
    void elSeedManualSoloLoPuedeEjecutarUnAdministrador() throws Exception {
        mockMvc.perform(post("/admin/seed")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/seed").with(jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "PASAJERO"))
                        .authorities(new SimpleGrantedAuthority("ROLE_PASAJERO"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/seed").with(jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "ADMIN"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itinerariosNuevos").value(0));
    }

    private UUID primerItinerarioDeManana() throws Exception {
        LocalDate manana = LocalDate.now(CatalogTime.ZONE).plusDays(1);
        String body = mockMvc.perform(get("/trips/search")
                        .param("origin", "Bogotá").param("destination", "Medellín").param("date", manana.toString()))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get(0).get("itineraryId").asText());
    }
}
