package com.tripbus.catalog;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.tripbus.catalog.client.SeatInventoryClient;
import com.tripbus.catalog.common.events.EventProperties;
import com.tripbus.catalog.common.observability.TracePropagation;
import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.repository.BusRepository;
import com.tripbus.catalog.repository.CompanyRepository;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.service.CatalogTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HT-10 contra PostgreSQL y Redis reales (Testcontainers), con tracing (muestreo del 100 %) y métricas encendidos:
 * la traza de una petición llega a Seat Inventory (header traceparent del RestClient) y a los eventos de Redis
 * Streams, el consumidor la retoma y las métricas propias salen en /actuator/prometheus. Aquí el consumidor sí corre.
 */
@SpringBootTest(properties = {"management.tracing.sampling.probability=1.0", "tripbus.seed.on-startup=false",
        "tripbus.events.consumer-enabled=true", "tripbus.events.stream=tripbus:events:obs"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
@DirtiesContext
class ObservabilityIT {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-b7ad6b7169203331-01";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final WireMockServer SEAT_INVENTORY = new WireMockServer(options().dynamicPort());

    static {
        SEAT_INVENTORY.start();
    }

    @DynamicPropertySource
    static void seatInventory(DynamicPropertyRegistry registry) {
        registry.add("tripbus.services.seat-inventory-url", SEAT_INVENTORY::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private EventProperties eventProperties;
    @Autowired
    private SeatInventoryClient seatInventoryClient;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private BusRepository busRepository;
    @Autowired
    private ItineraryRepository itineraryRepository;

    private UUID itineraryId;
    private LocalDate fecha;

    @BeforeAll
    void preparar() {
        SEAT_INVENTORY.stubFor(get(urlPathEqualTo("/seats/availability"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[]")));
        // La primera petición a WireMock en la JVM puede superar el read timeout del cliente (500 ms).
        for (int i = 0; i < 3; i++) {
            seatInventoryClient.availability(List.of(UUID.randomUUID()));
        }
        fecha = LocalDate.now(CatalogTime.ZONE).plusDays(2);
        itineraryId = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            Company company = companyRepository.save(new Company(UUID.randomUUID(), "Trazas SA", "800777001-1"));
            Route route = routeRepository.save(new Route(UUID.randomUUID(), company, "Obsville", "Trazópolis", 120));
            Bus bus = busRepository.save(new Bus(UUID.randomUUID(), company.getId(), "OBS001", BusType.UN_PISO, 40));
            Instant salida = fecha.atTime(LocalTime.of(10, 0)).atZone(CatalogTime.ZONE).toInstant();
            itineraryRepository.save(new Itinerary(itineraryId, route, bus, salida, salida.plus(Duration.ofHours(2)),
                    new BigDecimal("50000")));
        });
    }

    @AfterAll
    void detener() {
        SEAT_INVENTORY.stop();
    }

    @Test
    void laTrazaDeLaBusquedaLlegaASeatInventory() throws Exception {
        SEAT_INVENTORY.resetRequests();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/trips/search")
                        .param("origin", "obsville").param("destination", "trazopolis").param("date", fecha.toString())
                        .header("traceparent", TRACEPARENT))
                .andExpect(status().isOk());

        List<LoggedRequest> llamadas = SEAT_INVENTORY.findAll(getRequestedFor(urlPathEqualTo("/seats/availability")));
        assertThat(llamadas).isNotEmpty();
        assertThat(llamadas.get(0).getHeader("traceparent")).startsWith("00-" + TRACE_ID + "-");
    }

    @Test
    void elEventoPublicadoLlevaLaTrazaDeLaPeticion() throws Exception {
        mockMvc.perform(post("/companies").header("traceparent", TRACEPARENT)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "ADMIN"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Trazada SAS\",\"nit\":\"800777002-2\"}"))
                .andExpect(status().isCreated());

        List<MapRecord<String, Object, Object>> publicados = redisTemplate.opsForStream()
                .range(eventProperties.stream(), Range.unbounded());
        assertThat(publicados).anySatisfy(r -> {
            assertThat(r.getValue()).containsEntry("type", "CompanyCreated");
            assertThat(String.valueOf(r.getValue().get(TracePropagation.CAMPO))).startsWith("00-" + TRACE_ID + "-");
        });
    }

    @Test
    void elConsumidorRetomaLaTrazaYProcesaElEvento() {
        UUID offerId = UUID.randomUUID();
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("eventId", UUID.randomUUID().toString());
        campos.put("type", "OfferActivated");
        campos.put("source", "flash-offers-service");
        campos.put("occurredAt", Instant.now().toString());
        campos.put("payload", "{\"itineraryId\":\"" + itineraryId + "\",\"offerId\":\"" + offerId
                + "\",\"precioOferta\":39000}");
        campos.put(TracePropagation.CAMPO, TRACEPARENT);
        redisTemplate.opsForStream().add(StreamRecords.newRecord().in(eventProperties.stream()).ofMap(campos));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(itineraryRepository.findById(itineraryId).orElseThrow().getOfertaId()).isEqualTo(offerId));
    }

    @Test
    void lasMetricasPropiasSalenEnPrometheus() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/trips/search")
                        .param("origin", "Obsville").param("destination", "Trazópolis").param("date", fecha.toString()))
                .andExpect(status().isOk());

        String scrape = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/prometheus"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(scrape)
                .containsPattern("tripbus_busquedas_total\\{[^}]*cache=\"(hit|miss)\"[^}]*} [1-9]")
                .containsPattern("tripbus_busqueda_segundos_count\\{[^}]*} [1-9]")
                .contains("tripbus_viajes_programados_total");
    }
}
