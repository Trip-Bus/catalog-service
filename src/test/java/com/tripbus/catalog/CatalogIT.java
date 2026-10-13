package com.tripbus.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.catalog.common.events.EventEnvelope;
import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.events.OfferEventsHandler;
import com.tripbus.catalog.repository.BusRepository;
import com.tripbus.catalog.repository.CompanyRepository;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private BusRepository busRepository;
    @Autowired
    private ItineraryRepository itineraryRepository;

    @BeforeAll
    void sembrar() {
        seedService.seed();
    }

    @Test
    void elSeedEsIdempotente() {
        SeedResult segunda = seedService.seed();
        assertThat(segunda.itinerariosNuevos()).isZero();
        // 7 ciudades conectadas entre sí en los dos sentidos = 42 rutas, 5 salidas diarias, 23 días (ayer a +21).
        assertThat(segunda.rutas()).isEqualTo(7 * 6);
        assertThat(segunda.itinerariosExistentes()).isEqualTo(7 * 6 * 5 * 23);
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

    @Test
    void viajesEnRutaSoloDevuelveLosQueEstanEnCursoYSinPrecioNiAsientos() throws Exception {
        Instant now = Instant.now();
        Company empresa = companyRepository.save(new Company(UUID.randomUUID(), "Rápido En Ruta", null));
        Route ruta = routeRepository.save(new Route(UUID.randomUUID(), empresa, "Bogotá", "Tunja En Ruta", 180));
        Itinerary enCurso = itinerario(ruta, now.minus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS));
        Itinerary porSalir = itinerario(ruta, now.plus(20, ChronoUnit.MINUTES), now.plus(3, ChronoUnit.HOURS));
        Itinerary manana = itinerario(ruta, now.plus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS));
        Itinerary yaLlego = itinerario(ruta, now.minus(5, ChronoUnit.HOURS), now.minus(2, ChronoUnit.HOURS));
        Itinerary cancelado = itinerario(ruta, now.minus(30, ChronoUnit.MINUTES), now.plus(2, ChronoUnit.HOURS));
        cancelado.cancelar();
        itineraryRepository.saveAll(List.of(enCurso, porSalir, manana, yaLlego, cancelado));

        // Solo por destino (sin tilde ni mayúsculas): exactamente los dos de la ventana, ordenados por salida.
        String body = mockMvc.perform(get("/trips/in-progress").param("destination", "TUNJA en ruta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].itineraryId").value(enCurso.getId().toString()))
                .andExpect(jsonPath("$[0].busId").value(enCurso.getBus().getId().toString()))
                .andExpect(jsonPath("$[0].empresa").value("Rápido En Ruta"))
                .andExpect(jsonPath("$[0].origen").value("Bogotá"))
                .andExpect(jsonPath("$[0].destino").value("Tunja En Ruta"))
                .andExpect(jsonPath("$[1].itineraryId").value(porSalir.getId().toString()))
                .andReturn().getResponse().getContentAsString();
        JsonNode primero = objectMapper.readTree(body).get(0);
        assertThat(primero.has("fechaSalida")).isTrue();
        assertThat(primero.has("fechaLlegadaEstimada")).isTrue();
        assertThat(primero.has("precioBase")).isFalse();
        assertThat(primero.has("asientosDisponibles")).isFalse();
        assertThat(primero.has("ofertaActiva")).isFalse();

        // "bogota" sin tilde, solo origen: aparece el viaje en curso y nunca los que están fuera de la ventana.
        String porOrigen = mockMvc.perform(get("/trips/in-progress").param("origin", "bogota"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = new ArrayList<>();
        objectMapper.readTree(porOrigen).forEach(n -> ids.add(n.get("itineraryId").asText()));
        assertThat(ids).contains(enCurso.getId().toString(), porSalir.getId().toString())
                .doesNotContain(manana.getId().toString(), yaLlego.getId().toString(), cancelado.getId().toString());

        // Origen y destino juntos.
        mockMvc.perform(get("/trips/in-progress").param("origin", "BOGOTÁ").param("destination", "tunja en ruta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void viajesEnRutaSinOrigenNiDestinoEs400YEsPublico() throws Exception {
        mockMvc.perform(get("/trips/in-progress"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SEARCH"));
        mockMvc.perform(get("/trips/in-progress").param("origin", "  ").param("destination", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SEARCH"));
    }

    private Itinerary itinerario(Route ruta, Instant salida, Instant llegada) {
        String placa = "IT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Bus bus = busRepository.save(new Bus(UUID.randomUUID(), ruta.getCompany().getId(), placa, BusType.UN_PISO, 40));
        return new Itinerary(UUID.randomUUID(), ruta, bus, salida, llegada, new BigDecimal("80000"));
    }

    private UUID primerItinerarioDeManana() throws Exception {
        LocalDate manana = LocalDate.now(CatalogTime.ZONE).plusDays(1);
        String body = mockMvc.perform(get("/trips/search")
                        .param("origin", "Bogotá").param("destination", "Medellín").param("date", manana.toString()))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get(0).get("itineraryId").asText());
    }
}
