package com.tripbus.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.catalog.common.events.EventEnvelope;
import com.tripbus.catalog.events.OfferEventsHandler;
import com.tripbus.catalog.seed.SeedService;
import com.tripbus.catalog.service.CatalogTime;
import com.tripbus.catalog.web.dto.SeedResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Pruebas de integración con PostgreSQL y Redis reales (TRIPBUS_IT=true). */
@EnabledIfEnvironmentVariable(named = "TRIPBUS_IT", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestPropertySource(properties = {"tripbus.seed.on-startup=false", "SEAT_INVENTORY_URL=http://localhost:1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogIT {

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
    @Autowired
    private Flyway flyway;

    @BeforeAll
    void limpiarYSembrar() {
        flyway.clean();
        flyway.migrate();
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
        // Seat Inventory no está disponible en esta prueba: la búsqueda igual responde.
        assertThat(results.get(0).get("asientosDisponibles").isNull()).isTrue();
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
