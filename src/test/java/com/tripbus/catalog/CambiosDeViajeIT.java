package com.tripbus.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.tripbus.catalog.client.SeatInventoryClient;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.repository.BusRepository;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.service.CatalogTime;
import com.tripbus.catalog.service.ItineraryChangeService;
import com.tripbus.catalog.web.dto.ChangeBusRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-118, US-119 y la corrección de la cancelación contra PostgreSQL 16 y Redis 7 reales (Testcontainers) y
 * Seat Inventory simulado con WireMock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestPropertySource(properties = "tripbus.seed.on-startup=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class CambiosDeViajeIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final WireMockServer SEAT_INVENTORY = new WireMockServer(options().dynamicPort());
    private static final AtomicInteger SECUENCIA = new AtomicInteger();
    private static final String REMAP = "/internal/itineraries/.*/remap";

    static {
        SEAT_INVENTORY.start();
    }

    @DynamicPropertySource
    static void seatInventory(DynamicPropertyRegistry registry) {
        registry.add("tripbus.services.seat-inventory-url", SEAT_INVENTORY::baseUrl);
    }

    @AfterAll
    void detener() {
        SEAT_INVENTORY.stop();
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private ItineraryChangeService changeService;
    @Autowired
    private SeatInventoryClient seatInventoryClient;
    @Autowired
    private ItineraryRepository itineraryRepository;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private BusRepository busRepository;
    @Value("${tripbus.events.stream}")
    private String stream;
    @Value("${tripbus.internal-token}")
    private String internalToken;

    /**
     * La primera petición a WireMock en la JVM tarda más que el read timeout (500 ms) del cliente: se calienta
     * antes de las pruebas con el mismo SeatInventoryClient. Las peticiones se borran en cada @BeforeEach.
     */
    @BeforeAll
    void calentarSeatInventory() {
        seatInventoryResponde();
        for (int i = 0; i < 3; i++) {
            seatInventoryClient.remap(UUID.randomUUID(), List.of("1"), 1);
        }
    }

    @BeforeEach
    void seatInventoryResponde() {
        SEAT_INVENTORY.resetAll();
        SEAT_INVENTORY.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathMatching(REMAP))
                .willReturn(aResponse().withStatus(204)));
    }

    // ---------------------------------------------------------------- US-118

    @Test
    void retrasoYReprogramacionSegunElUmbralConHistorialYEvento() throws Exception {
        UUID empresa = crearEmpresa();
        String ruta = crearRuta(empresa, 60);
        UUID bus = crearBus(empresa);
        String viaje = crearViaje(empresa, ruta, bus, mananaA(8, 0));

        // +2 h: RETRASO (umbral 6 h). La llegada se recalcula con la duración de la ruta.
        mockMvc.perform(patch("/itineraries/{id}/departure", viaje).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(salida(mananaA(10, 0), "Derrumbe en la vía")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechaSalida").value(mananaA(10, 0).toString()))
                .andExpect(jsonPath("$.fechaLlegadaEstimada").value(mananaA(11, 0).toString()));
        // +7 h desde la salida actual: REPROGRAMACION. Adelantar también es REPROGRAMACION.
        mockMvc.perform(patch("/itineraries/{id}/departure", viaje).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(salida(mananaA(17, 0), "Cambio de horario")))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/itineraries/{id}/departure", viaje).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(salida(mananaA(16, 0), "Adelanto")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/itineraries/{id}/changes", viaje).with(operador(empresa)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].tipo").value("RETRASO"))
                .andExpect(jsonPath("$[0].salidaAnterior").value(mananaA(8, 0).toString()))
                .andExpect(jsonPath("$[0].salidaNueva").value(mananaA(10, 0).toString()))
                .andExpect(jsonPath("$[0].motivo").value("Derrumbe en la vía"))
                .andExpect(jsonPath("$[0].hechoPor").isNotEmpty())
                .andExpect(jsonPath("$[1].tipo").value("REPROGRAMACION"))
                .andExpect(jsonPath("$[2].tipo").value("REPROGRAMACION"));
        mockMvc.perform(get("/itineraries/{id}", viaje))
                .andExpect(jsonPath("$.fechaSalida").value(mananaA(16, 0).toString()));

        List<JsonNode> eventos = eventos("ItineraryRescheduled", viaje);
        assertThat(eventos).hasSize(3);
        JsonNode primero = eventos.get(0);
        assertThat(primero.get("minutosDiferencia").asLong()).isEqualTo(120);
        assertThat(primero.get("tipo").asText()).isEqualTo("RETRASO");
        assertThat(primero.get("companyId").asText()).isEqualTo(empresa.toString());
        assertThat(primero.get("routeId").asText()).isEqualTo(ruta);
        assertThat(primero.get("busId").asText()).isEqualTo(bus.toString());
        assertThat(Instant.parse(primero.get("fechaLlegada").asText())).isEqualTo(mananaA(11, 0));
        assertThat(eventos.get(2).get("minutosDiferencia").asLong()).isEqualTo(-60);
    }

    @Test
    void reprogramarRevalidaElCruceSinContarElPropioViaje() throws Exception {
        UUID empresa = crearEmpresa();
        String ruta = crearRuta(empresa, 60);
        UUID bus = crearBus(empresa);
        String temprano = crearViaje(empresa, ruta, bus, mananaA(8, 0));
        String tarde = crearViaje(empresa, ruta, bus, mananaA(12, 0));

        // 09:00 choca con el de las 08:00 (llega 09:00 + 30 min de alistamiento).
        mockMvc.perform(patch("/itineraries/{id}/departure", tarde).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(salida(mananaA(9, 0), "x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUS_SCHEDULE_CONFLICT"))
                .andExpect(jsonPath("$.error.details.itineraryId").value(temprano));
        // Correrlo 15 min se cruza solo consigo mismo: sí se puede.
        mockMvc.perform(patch("/itineraries/{id}/departure", temprano).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(salida(mananaA(8, 15), "x")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/itineraries/{id}/changes", tarde).with(operador(empresa)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void reglasDeLaReprogramacion() throws Exception {
        UUID empresa = crearEmpresa();
        UUID otra = crearEmpresa();
        String ruta = crearRuta(empresa, 60);
        UUID bus = crearBus(empresa);
        String viaje = crearViaje(empresa, ruta, bus, mananaA(8, 0));

        mockMvc.perform(patch("/itineraries/{id}/departure", viaje).with(operador(otra))
                        .contentType(MediaType.APPLICATION_JSON).content(salida(mananaA(9, 0), "x")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ITINERARY_NOT_FOUND"));
        mockMvc.perform(get("/itineraries/{id}/changes", viaje).with(operador(otra))).andExpect(status().isNotFound());
        mockMvc.perform(get("/itineraries/{id}/changes", viaje)).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/itineraries/{id}/departure", viaje).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(salida(Instant.now().minusSeconds(60), "x")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("DEPARTURE_IN_PAST"));
        mockMvc.perform(patch("/itineraries/{id}/departure", viaje).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nuevaFechaSalida\":null,\"motivo\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        // Un viaje que ya salió no se reprograma.
        String enCurso = viajeDirecto(ruta, bus, Instant.now().minus(10, ChronoUnit.MINUTES), 60);
        mockMvc.perform(patch("/itineraries/{id}/departure", enCurso).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(salida(mananaA(20, 0), "x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ITINERARY_ALREADY_DEPARTED"));

        cancelar(empresa, viaje).andExpect(status().isOk());
        mockMvc.perform(patch("/itineraries/{id}/departure", viaje).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(salida(mananaA(9, 0), "x")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ITINERARY_CANCELLED"));
    }

    // ---------------------------------------------------------------- US-119

    @Test
    void cambiarElBusReasignaLosAsientosEnSeatInventory() throws Exception {
        UUID empresa = crearEmpresa();
        String ruta = crearRuta(empresa, 60);
        UUID busA = crearBus(empresa);
        UUID busB = crearBus(empresa);
        mapaDeTresAsientos(empresa, busB);
        String viaje = crearViaje(empresa, ruta, busA, mananaA(8, 0));

        mockMvc.perform(patch("/itineraries/{id}/bus", viaje).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(cambioBus(busB, "Falla de frenos")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busId").value(busB.toString()));

        List<LoggedRequest> llamadas = SEAT_INVENTORY.findAll(
                postRequestedFor(urlEqualTo("/internal/itineraries/" + viaje + "/remap")));
        assertThat(llamadas).hasSize(1);
        JsonNode body = objectMapper.readTree(llamadas.get(0).getBodyAsString());
        assertThat(body.get("capacidad").asInt()).isEqualTo(3);
        assertThat(body.get("seatNumbers")).extracting(JsonNode::asText).containsExactly("1", "2", "3");
        SEAT_INVENTORY.verify(postRequestedFor(urlPathMatching(REMAP))
                .withHeader("X-Internal-Token", equalTo(internalToken)));

        mockMvc.perform(get("/itineraries/{id}/seatmap", viaje))
                .andExpect(jsonPath("$.busId").value(busB.toString()))
                .andExpect(jsonPath("$.asientos.length()").value(3));
        mockMvc.perform(get("/itineraries/{id}/changes", viaje).with(admin()))
                .andExpect(jsonPath("$[0].tipo").value("CAMBIO_BUS"))
                .andExpect(jsonPath("$[0].busAnterior").value(busA.toString()))
                .andExpect(jsonPath("$[0].busNuevo").value(busB.toString()));
        JsonNode evento = eventos("ItineraryBusChanged", viaje).get(0);
        assertThat(evento.get("busAnterior").asText()).isEqualTo(busA.toString());
        assertThat(evento.get("busNuevo").asText()).isEqualTo(busB.toString());
        assertThat(evento.get("capacidad").asInt()).isEqualTo(3);
        assertThat(evento.get("seatNumbers")).hasSize(3);
        assertThat(evento.get("companyId").asText()).isEqualTo(empresa.toString());

        // El bus anterior quedó libre a esa hora.
        crearViajeRespuesta(empresa, ruta, busA, mananaA(8, 0)).andExpect(status().isCreated());
    }

    @Test
    void conflictoDeAsientosNoCambiaNada() throws Exception {
        UUID empresa = crearEmpresa();
        String ruta = crearRuta(empresa, 60);
        UUID busA = crearBus(empresa);
        UUID busB = crearBus(empresa);
        mapaDeTresAsientos(empresa, busB);
        String viaje = crearViaje(empresa, ruta, busA, mananaA(8, 0));
        SEAT_INVENTORY.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathMatching(REMAP))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":\"SEATS_CONFLICT\",\"message\":\"x\","
                                + "\"details\":{\"asientos\":[\"12\",\"40\"]}}}")));

        mockMvc.perform(patch("/itineraries/{id}/bus", viaje).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(cambioBus(busB, "x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEATS_CONFLICT"))
                .andExpect(jsonPath("$.error.details.asientos[0]").value("12"))
                .andExpect(jsonPath("$.error.details.asientos[1]").value("40"));
        sinCambios(empresa, viaje, busA);
    }

    @Test
    void seatInventoryCaidoEs503YNoCambiaNada() throws Exception {
        UUID empresa = crearEmpresa();
        String ruta = crearRuta(empresa, 60);
        UUID busA = crearBus(empresa);
        UUID busB = crearBus(empresa);
        String viaje = crearViaje(empresa, ruta, busA, mananaA(8, 0));
        SEAT_INVENTORY.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathMatching(REMAP))
                .willReturn(aResponse().withStatus(204).withFixedDelay(1500)));

        mockMvc.perform(patch("/itineraries/{id}/bus", viaje).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(cambioBus(busB, "x")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SEAT_INVENTORY_UNAVAILABLE"));
        sinCambios(empresa, viaje, busA);
    }

    @Test
    void siCatalogFallaDespuesDelRemapSeCompensaConLosAsientosDelBusAnterior() throws Exception {
        UUID empresa = crearEmpresa();
        String ruta = crearRuta(empresa, 60);
        UUID busA = crearBus(empresa);
        UUID busB = crearBus(empresa);
        mapaDeTresAsientos(empresa, busB);
        UUID viaje = UUID.fromString(crearViaje(empresa, ruta, busA, mananaA(8, 0)));

        // Motivo más largo que la columna (300): el remap sale bien y la transacción de Catalog falla al confirmar.
        ChangeBusRequest invalido = new ChangeBusRequest(busB, "m".repeat(301));
        assertThatThrownBy(() -> changeService.changeBus(viaje, invalido, empresa, UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);

        List<LoggedRequest> llamadas = SEAT_INVENTORY.findAll(
                postRequestedFor(urlEqualTo("/internal/itineraries/" + viaje + "/remap")));
        assertThat(llamadas).hasSize(2);
        assertThat(objectMapper.readTree(llamadas.get(0).getBodyAsString()).get("capacidad").asInt()).isEqualTo(3);
        JsonNode compensacion = objectMapper.readTree(llamadas.get(1).getBodyAsString());
        assertThat(compensacion.get("capacidad").asInt()).isEqualTo(40);
        assertThat(compensacion.get("seatNumbers")).hasSize(40);
        sinCambios(empresa, viaje.toString(), busA);
    }

    @Test
    void reglasDelBusNuevo() throws Exception {
        UUID empresa = crearEmpresa();
        UUID otra = crearEmpresa();
        String ruta = crearRuta(empresa, 60);
        UUID busA = crearBus(empresa);
        UUID ocupado = crearBus(empresa);
        UUID deOtra = crearBus(otra);
        String viaje = crearViaje(empresa, ruta, busA, mananaA(8, 0));
        String delOcupado = crearViaje(empresa, ruta, ocupado, mananaA(8, 30));

        mockMvc.perform(patch("/itineraries/{id}/bus", viaje).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(cambioBus(ocupado, "x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUS_SCHEDULE_CONFLICT"))
                .andExpect(jsonPath("$.error.details.itineraryId").value(delOcupado));
        mockMvc.perform(patch("/itineraries/{id}/bus", viaje).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(cambioBus(deOtra, "x")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BUS_NOT_FOUND"));
        mockMvc.perform(patch("/itineraries/{id}/bus", viaje).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(cambioBus(deOtra, "x")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMPANY_MISMATCH"));
        mockMvc.perform(patch("/itineraries/{id}/bus", viaje).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(cambioBus(busA, "x")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("SAME_BUS"));
        mockMvc.perform(patch("/itineraries/{id}/bus", viaje).with(operador(otra))
                        .contentType(MediaType.APPLICATION_JSON).content(cambioBus(deOtra, "x")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ITINERARY_NOT_FOUND"));

        UUID enTaller = crearBus(empresa);
        mockMvc.perform(patch("/buses/{id}/estado", enTaller).with(operador(empresa))
                .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"MANTENIMIENTO\"}")).andExpect(status().isOk());
        mockMvc.perform(patch("/itineraries/{id}/bus", viaje).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(cambioBus(enTaller, "x")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("BUS_NOT_ACTIVE"));
        SEAT_INVENTORY.verify(0, postRequestedFor(urlPathMatching(REMAP)));
    }

    // ---------------------------------------------------------------- cancelación

    @Test
    void cancelarSoloViajesDeLaEmpresaYNoTerminados() throws Exception {
        UUID empresa = crearEmpresa();
        UUID otra = crearEmpresa();
        String ruta = crearRuta(empresa, 60);
        UUID bus = crearBus(empresa);
        String viaje = crearViaje(empresa, ruta, bus, mananaA(8, 0));

        cancelar(otra, viaje).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ITINERARY_NOT_FOUND"));
        String terminado = viajeDirecto(ruta, bus, Instant.now().minus(3, ChronoUnit.HOURS), 60);
        cancelar(empresa, terminado).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ITINERARY_FINISHED"));

        cancelar(empresa, viaje).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CANCELADO"));
        cancelar(empresa, viaje).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ITINERARY_ALREADY_CANCELLED"));
        mockMvc.perform(get("/itineraries/{id}/changes", viaje).with(operador(empresa)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tipo").value("CANCELACION"))
                .andExpect(jsonPath("$[0].motivo").value("Paro de transportadores"))
                .andExpect(jsonPath("$[0].salidaAnterior").value(mananaA(8, 0).toString()));

        JsonNode evento = eventos("ItineraryCancelled", viaje).get(0);
        assertThat(evento.get("companyId").asText()).isEqualTo(empresa.toString());
        assertThat(evento.get("routeId").asText()).isEqualTo(ruta);
        assertThat(Instant.parse(evento.get("fechaSalida").asText())).isEqualTo(mananaA(8, 0));
        assertThat(evento.get("motivo").asText()).isEqualTo("Paro de transportadores");
    }

    // ---------------------------------------------------------------- utilidades

    private void sinCambios(UUID empresa, String viaje, UUID busOriginal) throws Exception {
        mockMvc.perform(get("/itineraries/{id}", viaje)).andExpect(jsonPath("$.busId").value(busOriginal.toString()));
        mockMvc.perform(get("/itineraries/{id}/changes", viaje).with(operador(empresa)))
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(eventos("ItineraryBusChanged", viaje)).isEmpty();
    }

    private ResultActions cancelar(UUID empresa, String viaje) throws Exception {
        return mockMvc.perform(post("/admin/itineraries/{id}/cancel", viaje).with(operador(empresa))
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Paro de transportadores\"}"));
    }

    /** Viaje guardado directo en la base (para tener viajes que ya salieron o terminaron). */
    private String viajeDirecto(String ruta, UUID bus, Instant salida, int minutos) {
        Itinerary i = new Itinerary(UUID.randomUUID(), routeRepository.findWithCompany(UUID.fromString(ruta)).orElseThrow(),
                busRepository.findById(bus).orElseThrow(), salida, salida.plus(minutos, ChronoUnit.MINUTES),
                new BigDecimal("40000"));
        return itineraryRepository.saveAndFlush(i).getId().toString();
    }

    private String crearViaje(UUID empresa, String ruta, UUID bus, Instant salida) throws Exception {
        return json(crearViajeRespuesta(empresa, ruta, bus, salida).andExpect(status().isCreated()).andReturn())
                .get("itineraryId").asText();
    }

    private ResultActions crearViajeRespuesta(UUID empresa, String ruta, UUID bus, Instant salida) throws Exception {
        String body = objectMapper.createObjectNode().put("routeId", ruta).put("busId", bus.toString())
                .put("fechaSalida", salida.toString()).put("precioBase", 50000).toString();
        return mockMvc.perform(post("/itineraries").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String crearRuta(UUID empresa, int minutos) throws Exception {
        String origen = "Origen CV" + SECUENCIA.incrementAndGet();
        String destino = "Destino CV" + SECUENCIA.incrementAndGet();
        ObjectNode ruta = objectMapper.createObjectNode().put("origen", origen).put("destino", destino);
        ArrayNode paradas = ruta.putArray("paradas");
        paradas.addObject().put("orden", 1).put("nombre", origen).put("lat", 4.6).put("lng", -74.1)
                .put("minutosDesdeSalida", 0);
        paradas.addObject().put("orden", 2).put("nombre", destino).put("lat", 5.5).put("lng", -73.3)
                .put("minutosDesdeSalida", minutos);
        return json(mockMvc.perform(post("/routes").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                .content(ruta.toString())).andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    private UUID crearEmpresa() throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("nombre", "Empresa CV " + SECUENCIA.incrementAndGet())
                .put("nit", String.format("6%08d", SECUENCIA.incrementAndGet()));
        return UUID.fromString(json(mockMvc.perform(post("/companies").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
    }

    private UUID crearBus(UUID empresa) throws Exception {
        int n = SECUENCIA.incrementAndGet();
        String placa = "CV" + (char) ('A' + (n / 1000) % 26) + String.format("%03d", n % 1000);
        return UUID.fromString(json(mockMvc.perform(post("/buses").with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placa\":\"" + placa + "\",\"tipo\":\"UN_PISO\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
    }

    private void mapaDeTresAsientos(UUID empresa, UUID bus) throws Exception {
        ObjectNode mapa = objectMapper.createObjectNode();
        ObjectNode piso = mapa.putArray("pisos").addObject().put("piso", 1).put("filas", 2).put("columnas", 3);
        ArrayNode celdas = piso.putArray("celdas");
        celdas.addObject().put("fila", 0).put("columna", 1).put("tipo", "CONDUCTOR");
        for (int c = 1; c <= 3; c++) {
            celdas.addObject().put("fila", 1).put("columna", c).put("tipo", "ASIENTO").put("asiento", String.valueOf(c));
        }
        mockMvc.perform(put("/buses/{id}/seatmap", bus).with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                .content(mapa.toString())).andExpect(status().isOk());
    }

    private String salida(Instant nueva, String motivo) {
        return objectMapper.createObjectNode().put("nuevaFechaSalida", nueva.toString()).put("motivo", motivo).toString();
    }

    private String cambioBus(UUID bus, String motivo) {
        return objectMapper.createObjectNode().put("busId", bus.toString()).put("motivo", motivo).toString();
    }

    private static Instant mananaA(int hora, int minuto) {
        return LocalDate.now(CatalogTime.ZONE).plusDays(1).atTime(LocalTime.of(hora, minuto))
                .atZone(CatalogTime.ZONE).toInstant();
    }

    /** Payloads de los eventos de ese tipo para el viaje, en orden de publicación. */
    private List<JsonNode> eventos(String tipo, String itineraryId) throws Exception {
        List<JsonNode> eventos = new ArrayList<>();
        for (var r : redisTemplate.opsForStream().range(stream, Range.unbounded())) {
            JsonNode payload = objectMapper.readTree(String.valueOf(r.getValue().get("payload")));
            if (tipo.equals(String.valueOf(r.getValue().get("type")))
                    && itineraryId.equals(payload.path("itineraryId").asText())) {
                eventos.add(payload);
            }
        }
        return eventos;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static RequestPostProcessor admin() {
        return usuario("ADMIN", null);
    }

    private static RequestPostProcessor operador(UUID companyId) {
        return usuario("OPERADOR", companyId);
    }

    private static RequestPostProcessor usuario(String rol, UUID companyId) {
        return jwt().jwt(j -> {
            j.subject(UUID.randomUUID().toString()).claim("role", rol);
            if (companyId != null) {
                j.claim("companyId", companyId.toString());
            }
        }).authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }
}
