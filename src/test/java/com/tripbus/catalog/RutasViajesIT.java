package com.tripbus.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.repository.BusRepository;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.seed.SeedService;
import com.tripbus.catalog.service.CatalogTime;
import com.tripbus.catalog.service.ItineraryPlanningService;
import com.tripbus.catalog.web.dto.CreateItineraryRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * US-114, US-116 y US-117 contra PostgreSQL 16 y Redis 7 reales (Testcontainers, requiere Docker): rutas y
 * paradas, programación de viajes, cruces de horario (con el margen de alistamiento), concurrencia y la
 * restricción EXCLUDE de la base.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestPropertySource(properties = {"tripbus.seed.on-startup=false", "SEAT_INVENTORY_URL=http://localhost:1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class RutasViajesIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SeedService seedService;
    @Autowired
    private ItineraryPlanningService planningService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private BusRepository busRepository;
    @Autowired
    private ItineraryRepository itineraryRepository;
    @Value("${tripbus.events.stream}")
    private String stream;

    @BeforeAll
    void sembrar() {
        seedService.seed();
    }

    // ---------------------------------------------------------------- US-114 · rutas y paradas

    @Test
    void crudDeRutasConParadasYEventos() throws Exception {
        UUID empresa = crearEmpresa();
        String origen = ciudad("Pasto");
        String destino = ciudad("Ipiales");
        JsonNode ruta = json(mockMvc.perform(post("/routes").with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(rutaJson(null, origen, destino, 120).toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyId").value(empresa.toString()))
                .andExpect(jsonPath("$.origen").value(origen))
                .andExpect(jsonPath("$.duracionMinutos").value(120))
                .andExpect(jsonPath("$.estado").value("ACTIVA"))
                .andExpect(jsonPath("$.paradas.length()").value(3))
                .andReturn());
        String id = ruta.get("id").asText();

        // Las paradas siguen públicas con el formato de siempre (fleet).
        mockMvc.perform(get("/routes/{id}/stops", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].orden").value(1))
                .andExpect(jsonPath("$[0].nombre").value(origen))
                .andExpect(jsonPath("$[2].minutosDesdeSalida").value(120));
        mockMvc.perform(get("/routes/{id}", id).with(operador(empresa)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paradas.length()").value(3));
        mockMvc.perform(get("/routes").with(operador(empresa)).param("origen", origen.toUpperCase())
                        .param("destino", destino).param("estado", "ACTIVA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].paradas").doesNotExist());

        // Reemplazar solo las paradas (2) recalcula la duración.
        ArrayNode dos = objectMapper.createArrayNode();
        dos.add(parada(1, origen, 4.6, -74.1, 0)).add(parada(2, destino, 5.5, -73.3, 95));
        mockMvc.perform(put("/routes/{id}/stops", id).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(dos.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duracionMinutos").value(95))
                .andExpect(jsonPath("$.paradas.length()").value(2));
        mockMvc.perform(get("/routes/{id}/stops", id)).andExpect(jsonPath("$.length()").value(2));

        String otroDestino = ciudad("Tumaco");
        mockMvc.perform(put("/routes/{id}", id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(rutaJson(null, origen, otroDestino, 300).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destino").value(otroDestino))
                .andExpect(jsonPath("$.duracionMinutos").value(300));
        mockMvc.perform(patch("/routes/{id}/estado", id).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"INACTIVA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("INACTIVA"));
        mockMvc.perform(patch("/routes/{id}/estado", id).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"INACTIVA\"}"))
                .andExpect(status().isOk());

        List<JsonNode> eventos = eventos("routeId", id);
        assertThat(eventos).extracting(e -> e.get("type").asText())
                .containsExactly("RouteCreated", "RouteUpdated", "RouteUpdated", "RouteUpdated");
        JsonNode creada = eventos.get(0).get("payload");
        assertThat(creada.get("companyId").asText()).isEqualTo(empresa.toString());
        assertThat(creada.get("destino").asText()).isEqualTo(destino);
        assertThat(creada.get("paradas")).hasSize(3);
        assertThat(creada.get("paradas").get(1).get("lat").asDouble()).isEqualTo(5.0);
        assertThat(eventos.get(3).get("payload").get("estado").asText()).isEqualTo("INACTIVA");
    }

    @Test
    void validacionesYPermisosDeRutas() throws Exception {
        UUID empresa = crearEmpresa();
        UUID otra = crearEmpresa();
        ObjectNode mala = rutaJson(null, "Neiva", "neiva", 60);
        ((ObjectNode) mala.get("paradas").get(1)).put("lat", 40.0).put("orden", 7);
        mockMvc.perform(post("/routes").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(mala.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ROUTE"))
                .andExpect(jsonPath("$.error.details.destino").exists())
                .andExpect(jsonPath("$.error.details['paradas[1].lat']").exists())
                .andExpect(jsonPath("$.error.details['paradas[1].orden']").exists());
        mockMvc.perform(post("/routes").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(rutaJson(null, ciudad("A"), ciudad("B"), 60).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMPANY_REQUIRED"));
        mockMvc.perform(post("/routes").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(rutaJson(otra, ciudad("A"), ciudad("B"), 60).toString()))
                .andExpect(status().isForbidden());

        String id = crearRuta(empresa, 60);
        mockMvc.perform(get("/routes/{id}", id).with(operador(otra))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"));
        mockMvc.perform(put("/routes/{id}/stops", id).with(operador(otra)).contentType(MediaType.APPLICATION_JSON)
                .content("[]")).andExpect(status().isNotFound());
        mockMvc.perform(patch("/routes/{id}/estado", id).with(operador(otra)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"estado\":\"INACTIVA\"}")).andExpect(status().isNotFound());
        mockMvc.perform(get("/routes").with(operador(otra))).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/routes").param("companyId", empresa.toString()).with(admin()))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/routes")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/routes/{id}", id)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/routes").with(usuario("PASAJERO", null))).andExpect(status().isForbidden());

        // Empresa inactiva: no crea ni edita rutas.
        inactivarEmpresa(empresa);
        mockMvc.perform(post("/routes").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(rutaJson(null, ciudad("A"), ciudad("B"), 60).toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMPANY_INACTIVE"));
        JsonNode actual = json(mockMvc.perform(get("/routes/{id}", id).with(operador(empresa))).andReturn());
        mockMvc.perform(put("/routes/{id}", id).with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(rutaJson(null, actual.get("origen").asText(), actual.get("destino").asText(), 90)
                                .toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMPANY_INACTIVE"));
    }

    @Test
    void rutaConViajesFuturosNoCambiaSusParadas() throws Exception {
        UUID empresa = crearEmpresa();
        String ruta = crearRuta(empresa, 60);
        UUID bus = crearBus(empresa);
        crearViaje(empresa, ruta, bus, mananaA(8, 0)).andExpect(status().isCreated());
        JsonNode actual = json(mockMvc.perform(get("/routes/{id}", ruta).with(operador(empresa))).andReturn());
        String origen = actual.get("origen").asText();
        String destino = actual.get("destino").asText();

        // Mismas paradas: no hay cambio, responde 200.
        mockMvc.perform(put("/routes/{id}", ruta).with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(rutaJson(null, origen, destino, 60).toString()))
                .andExpect(status().isOk());
        ArrayNode otras = objectMapper.createArrayNode();
        otras.add(parada(1, origen, 4.6, -74.1, 0)).add(parada(2, destino, 5.5, -73.3, 80));
        mockMvc.perform(put("/routes/{id}/stops", ruta).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(otras.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROUTE_IN_USE"));
        mockMvc.perform(get("/routes/{id}/stops", ruta)).andExpect(jsonPath("$.length()").value(3));
    }

    // ---------------------------------------------------------------- US-116 · programar viajes

    @Test
    void programarUnViajePublicaElPayloadConLosAsientosRealesDelBus() throws Exception {
        UUID empresa = crearEmpresa();
        String ruta = crearRuta(empresa, 120);
        UUID bus = crearBus(empresa);
        mockMvc.perform(put("/buses/{id}/seatmap", bus).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content(mapaDeTresAsientos()))
                .andExpect(status().isOk());
        JsonNode rutaJson = json(mockMvc.perform(get("/routes/{id}", ruta).with(operador(empresa))).andReturn());
        LocalDate manana = LocalDate.now(CatalogTime.ZONE).plusDays(1);
        // La búsqueda queda en cache vacía; al crear el viaje se invalida.
        buscar(rutaJson, manana).andExpect(jsonPath("$.length()").value(0));

        Instant salida = mananaA(9, 15);
        JsonNode viaje = json(crearViaje(empresa, ruta, bus, salida)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.routeId").value(ruta))
                .andExpect(jsonPath("$.busId").value(bus.toString()))
                .andExpect(jsonPath("$.estado").value("PROGRAMADO"))
                .andExpect(jsonPath("$.precioBase").value(85000))
                .andExpect(jsonPath("$.moneda").value("COP"))
                .andReturn());
        String id = viaje.get("itineraryId").asText();
        assertThat(Instant.parse(viaje.get("fechaLlegadaEstimada").asText())).isEqualTo(salida.plus(120, ChronoUnit.MINUTES));

        mockMvc.perform(get("/itineraries/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.capacidad").value(3));
        buscar(rutaJson, manana).andExpect(jsonPath("$.length()").value(1));

        List<JsonNode> publicados = eventos("itineraryId", id);
        assertThat(publicados).hasSize(1);
        JsonNode payload = publicados.get(0).get("payload");
        assertThat(publicados.get(0).get("type").asText()).isEqualTo("ItineraryPublished");
        assertThat(payload.get("capacidad").asInt()).isEqualTo(3);
        assertThat(payload.get("seatNumbers")).extracting(JsonNode::asText).containsExactly("1", "2", "3");
        assertThat(payload.get("companyId").asText()).isEqualTo(empresa.toString());
        assertThat(payload.get("tipoBus").asText()).isEqualTo("UN_PISO");
        assertThat(payload.fieldNames()).toIterable().containsExactly("itineraryId", "routeId", "busId", "companyId",
                "origen", "destino", "fechaSalida", "fechaLlegada", "precioBase", "tipoBus", "capacidad", "seatNumbers");
    }

    @Test
    void validacionesAlProgramarUnViaje() throws Exception {
        UUID empresa = crearEmpresa();
        UUID otra = crearEmpresa();
        String ruta = crearRuta(empresa, 60);
        UUID bus = crearBus(empresa);
        UUID busOtra = crearBus(otra);
        Instant manana = mananaA(6, 0);

        mockMvc.perform(post("/itineraries").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(viajeJson(ruta, bus.toString(), manana, "0")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_PRICE"));
        mockMvc.perform(post("/itineraries").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(viajeJson(ruta, bus.toString(), manana, "-5")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_PRICE"));
        mockMvc.perform(post("/itineraries").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(viajeJson(ruta, bus.toString(), Instant.now().minus(1, ChronoUnit.HOURS), "50000")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("DEPARTURE_IN_PAST"));
        mockMvc.perform(post("/itineraries").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"" + ruta + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/itineraries").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(viajeJson(UUID.randomUUID().toString(), bus.toString(), manana, "50000")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"));
        // Bus de otra empresa: para el operador no existe; para el ADMIN no es de la empresa de la ruta.
        mockMvc.perform(post("/itineraries").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(viajeJson(ruta, busOtra.toString(), manana, "50000")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BUS_NOT_FOUND"));
        mockMvc.perform(post("/itineraries").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(viajeJson(ruta, busOtra.toString(), manana, "50000")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMPANY_MISMATCH"));

        mockMvc.perform(patch("/buses/{id}/estado", bus).with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"estado\":\"MANTENIMIENTO\"}")).andExpect(status().isOk());
        crearViaje(empresa, ruta, bus, manana)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("BUS_NOT_ACTIVE"));
        mockMvc.perform(patch("/buses/{id}/estado", bus).with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"estado\":\"ACTIVO\"}")).andExpect(status().isOk());

        mockMvc.perform(patch("/routes/{id}/estado", ruta).with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"estado\":\"INACTIVA\"}")).andExpect(status().isOk());
        crearViaje(empresa, ruta, bus, manana)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROUTE_INACTIVE"));
        mockMvc.perform(patch("/routes/{id}/estado", ruta).with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"estado\":\"ACTIVA\"}")).andExpect(status().isOk());

        inactivarEmpresa(empresa);
        crearViaje(empresa, ruta, bus, manana)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMPANY_INACTIVE"));

        mockMvc.perform(post("/itineraries").contentType(MediaType.APPLICATION_JSON)
                .content(viajeJson(ruta, bus.toString(), manana, "50000"))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/itineraries")).andExpect(status().isUnauthorized());
    }

    @Test
    void programacionRecurrenteCreaLoQuePuedeYListaLosViajes() throws Exception {
        UUID empresa = crearEmpresa();
        String ruta = crearRuta(empresa, 120);
        UUID bus = crearBus(empresa);
        LocalDate manana = LocalDate.now(CatalogTime.ZONE).plusDays(1);
        // Un viaje que ya ocupa al bus el cuarto día a las 07:00 (llega 09:00): choca con el de las 06:00.
        String existente = json(crearViaje(empresa, ruta, bus,
                manana.plusDays(3).atTime(7, 0).atZone(CatalogTime.ZONE).toInstant())
                .andExpect(status().isCreated()).andReturn()).get("itineraryId").asText();

        JsonNode r = json(mockMvc.perform(post("/itineraries/schedule").with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleJson(ruta, bus, "06:00", manana, manana.plusDays(9),
                                "LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creados.length()").value(9))
                .andExpect(jsonPath("$.omitidos.length()").value(1))
                .andExpect(jsonPath("$.omitidos[0].fecha").value(manana.plusDays(3).toString()))
                .andExpect(jsonPath("$.omitidos[0].codigo").value("BUS_SCHEDULE_CONFLICT"))
                .andExpect(jsonPath("$.omitidos[0].conflictoCon").value(existente))
                .andReturn());
        Instant primera = Instant.parse(r.get("creados").get(0).get("fechaSalida").asText());
        assertThat(primera).isEqualTo(manana.atTime(6, 0).atZone(CatalogTime.ZONE).toInstant());

        // Fechas pasadas se omiten sin fallar; solo los días pedidos.
        LocalDate ayer = LocalDate.now(CatalogTime.ZONE).minusDays(1);
        mockMvc.perform(post("/itineraries/schedule").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleJson(ruta, bus, "23:00", ayer, ayer, diaSemana(ayer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creados.length()").value(0))
                .andExpect(jsonPath("$.omitidos[0].codigo").value("DEPARTURE_IN_PAST"));
        mockMvc.perform(post("/itineraries/schedule").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleJson(ruta, bus, "06:00", manana, manana.plusDays(90), "LUNES")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SCHEDULE"));
        mockMvc.perform(post("/itineraries/schedule").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleJson(ruta, bus, "06:00", manana, manana.plusDays(3))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/itineraries").with(operador(empresa)).param("routeId", ruta).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(10))
                .andExpect(jsonPath("$.content[0].fechaSalida").value(primera.toString()));
        mockMvc.perform(get("/itineraries").with(operador(empresa)).param("busId", bus.toString())
                        .param("desde", manana.toString()).param("hasta", manana.plusDays(1).toString()))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/itineraries").with(admin()).param("companyId", empresa.toString())
                        .param("estado", "CANCELADO"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/itineraries").with(operador(crearEmpresa())).param("routeId", ruta))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---------------------------------------------------------------- US-117 · sin cruces

    @Test
    void unBusNoPuedeTenerViajesCruzadosNiSinAlistamiento() throws Exception {
        UUID empresa = crearEmpresa();
        String ruta = crearRuta(empresa, 120);
        UUID bus = crearBus(empresa);
        String primero = json(crearViaje(empresa, ruta, bus, mananaA(8, 0))
                .andExpect(status().isCreated()).andReturn()).get("itineraryId").asText();

        // Llega 10:00; con 30 min de alistamiento puede volver a salir desde las 10:30.
        crearViaje(empresa, ruta, bus, mananaA(9, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUS_SCHEDULE_CONFLICT"))
                .andExpect(jsonPath("$.error.details.itineraryId").value(primero));
        crearViaje(empresa, ruta, bus, mananaA(10, 20))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.itineraryId").value(primero));
        crearViaje(empresa, ruta, bus, mananaA(5, 45))
                .andExpect(status().isConflict());
        crearViaje(empresa, ruta, bus, mananaA(10, 30)).andExpect(status().isCreated());
        crearViaje(empresa, ruta, bus, mananaA(5, 30)).andExpect(status().isCreated());

        // Un viaje cancelado libera el bus.
        mockMvc.perform(post("/admin/itineraries/{id}/cancel", primero).with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"prueba\"}"))
                .andExpect(status().isOk());
        crearViaje(empresa, ruta, bus, mananaA(8, 0)).andExpect(status().isCreated());
    }

    @Test
    void veinteSolicitudesSimultaneasCruzadasCreanExactamenteUnViaje() throws Exception {
        UUID empresa = crearEmpresa();
        UUID ruta = UUID.fromString(crearRuta(empresa, 180));
        UUID bus = crearBus(empresa);
        Instant base = mananaA(12, 0);
        int hilos = 20;
        CountDownLatch listos = new CountDownLatch(hilos);
        CountDownLatch arranque = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        List<Future<String>> resultados = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            Instant salida = base.plus(i, ChronoUnit.MINUTES);
            resultados.add(pool.submit(() -> {
                listos.countDown();
                arranque.await();
                try {
                    planningService.create(new CreateItineraryRequest(ruta, bus, salida, new BigDecimal("60000")),
                            empresa);
                    return "CREADO";
                } catch (ApiException e) {
                    return e.getCode();
                }
            }));
        }
        listos.await(10, TimeUnit.SECONDS);
        arranque.countDown();
        List<String> codigos = Collections.synchronizedList(new ArrayList<>());
        for (Future<String> f : resultados) {
            codigos.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(codigos).filteredOn("CREADO"::equals).hasSize(1);
        assertThat(codigos).filteredOn("BUS_SCHEDULE_CONFLICT"::equals).hasSize(hilos - 1);
        assertThat(jdbc.queryForObject("select count(*) from itineraries where bus_id = ?", Integer.class, bus))
                .isEqualTo(1);
    }

    @Test
    void laBaseImpideCrucesRealesAunqueSeSalteElServicio() throws Exception {
        UUID empresa = crearEmpresa();
        UUID ruta = UUID.fromString(crearRuta(empresa, 120));
        UUID bus = crearBus(empresa);
        Instant salida = mananaA(14, 0);
        var route = routeRepository.findWithCompany(ruta).orElseThrow();
        var b = busRepository.findById(bus).orElseThrow();
        itineraryRepository.saveAndFlush(new Itinerary(UUID.randomUUID(), route, b, salida,
                salida.plus(2, ChronoUnit.HOURS), new BigDecimal("1000")));

        Itinerary cruzado = new Itinerary(UUID.randomUUID(), route, b, salida.plus(1, ChronoUnit.HOURS),
                salida.plus(3, ChronoUnit.HOURS), new BigDecimal("1000"));
        assertThatThrownBy(() -> itineraryRepository.saveAndFlush(cruzado))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Cancelado no cuenta; y pegado al anterior (sin margen) la base sí lo permite.
        Itinerary cancelado = new Itinerary(UUID.randomUUID(), route, b, salida.plus(1, ChronoUnit.HOURS),
                salida.plus(3, ChronoUnit.HOURS), new BigDecimal("1000"));
        cancelado.cancelar();
        itineraryRepository.saveAndFlush(cancelado);
        itineraryRepository.saveAndFlush(new Itinerary(UUID.randomUUID(), route, b, salida.plus(2, ChronoUnit.HOURS),
                salida.plus(4, ChronoUnit.HOURS), new BigDecimal("1000")));
    }

    @Test
    void elSeedNoGeneraCrucesYUsaBusAlternoEnRutasLargas() {
        assertThat(seedService.seed().itinerariosNuevos()).isZero();
        Integer cruces = jdbc.queryForObject("""
                select count(*) from itineraries a
                join itineraries b on a.bus_id = b.bus_id and a.id < b.id
                where a.estado <> 'CANCELADO' and b.estado <> 'CANCELADO'
                  and a.fecha_salida < b.fecha_llegada_estimada + interval '30 minutes'
                  and b.fecha_salida < a.fecha_llegada_estimada + interval '30 minutes'
                """, Integer.class);
        assertThat(cruces).isZero();

        UUID base = SeedService.id("bus:car-vil:0");
        UUID alterno = SeedService.busAlterno(base);
        Integer buses = jdbc.queryForObject("""
                select count(distinct i.bus_id) from itineraries i
                where i.route_id = ? and (i.fecha_salida at time zone 'America/Bogota')::time = time '05:30'
                """, Integer.class, SeedService.id("route:car-vil"));
        assertThat(buses).isEqualTo(2);
        assertThat(busRepository.findById(alterno)).get()
                .satisfies(b -> assertThat(b.getPlaca()).startsWith("U"));

        // La migración V5 calcula el mismo id que SeedService.busAlterno (UUID v3).
        UUID enSql = jdbc.queryForObject("""
                select (substr(h, 1, 12) || '3' || substr(h, 14, 3)
                        || to_hex((('x' || substr(h, 17, 1))::bit(4)::int & 3) | 8) || substr(h, 18, 15))::uuid
                from (select md5('tripbus:bus-alterno:' || ?::uuid) as h) s
                """, UUID.class, base);
        assertThat(enSql).isEqualTo(alterno);
    }

    // ---------------------------------------------------------------- utilidades

    private ResultActions crearViaje(UUID empresa, String ruta, UUID bus, Instant salida) throws Exception {
        return mockMvc.perform(post("/itineraries").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                .content(viajeJson(ruta, bus.toString(), salida, "85000")));
    }

    private ResultActions buscar(JsonNode ruta, LocalDate fecha) throws Exception {
        return mockMvc.perform(get("/trips/search").param("origin", ruta.get("origen").asText())
                        .param("destination", ruta.get("destino").asText()).param("date", fecha.toString()))
                .andExpect(status().isOk());
    }

    private String crearRuta(UUID empresa, int minutos) throws Exception {
        return json(mockMvc.perform(post("/routes").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(rutaJson(null, ciudad("Origen"), ciudad("Destino"), minutos).toString()))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    private UUID crearEmpresa() throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("nombre", "Empresa RV " + SECUENCIA.incrementAndGet())
                .put("nit", String.format("7%08d", SECUENCIA.incrementAndGet()));
        return UUID.fromString(json(mockMvc.perform(post("/companies").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
    }

    private void inactivarEmpresa(UUID empresa) throws Exception {
        mockMvc.perform(patch("/companies/{id}/estado", empresa).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"estado\":\"INACTIVA\"}")).andExpect(status().isOk());
    }

    private UUID crearBus(UUID empresa) throws Exception {
        int n = SECUENCIA.incrementAndGet();
        String placa = "RV" + (char) ('A' + (n / 1000) % 26) + String.format("%03d", n % 1000);
        return UUID.fromString(json(mockMvc.perform(post("/buses").with(operador(empresa))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placa\":\"" + placa + "\",\"tipo\":\"UN_PISO\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
    }

    private ObjectNode rutaJson(UUID companyId, String origen, String destino, int minutos) {
        ObjectNode ruta = objectMapper.createObjectNode().put("origen", origen).put("destino", destino);
        if (companyId != null) {
            ruta.put("companyId", companyId.toString());
        }
        ruta.putArray("paradas")
                .add(parada(1, origen, 4.6, -74.1, 0))
                .add(parada(2, "Parada " + origen, 5.0, -74.0, minutos / 2))
                .add(parada(3, destino, 5.5, -73.3, minutos));
        return ruta;
    }

    private ObjectNode parada(int orden, String nombre, double lat, double lng, int minutos) {
        return objectMapper.createObjectNode().put("orden", orden).put("nombre", nombre).put("lat", lat)
                .put("lng", lng).put("minutosDesdeSalida", minutos);
    }

    private String viajeJson(String ruta, String bus, Instant salida, String precio) {
        return objectMapper.createObjectNode().put("routeId", ruta).put("busId", bus)
                .put("fechaSalida", salida.toString()).put("precioBase", new BigDecimal(precio)).toString();
    }

    private String scheduleJson(String ruta, UUID bus, String hora, LocalDate desde, LocalDate hasta, String... dias) {
        ObjectNode body = objectMapper.createObjectNode().put("routeId", ruta).put("busId", bus.toString())
                .put("horaSalida", hora).put("desde", desde.toString()).put("hasta", hasta.toString())
                .put("precioBase", 70000);
        ArrayNode d = body.putArray("diasSemana");
        for (String dia : dias) {
            d.add(dia);
        }
        return body.toString();
    }

    private static String diaSemana(LocalDate fecha) {
        return switch (fecha.getDayOfWeek()) {
            case MONDAY -> "LUNES";
            case TUESDAY -> "MARTES";
            case WEDNESDAY -> "MIERCOLES";
            case THURSDAY -> "JUEVES";
            case FRIDAY -> "VIERNES";
            case SATURDAY -> "SABADO";
            case SUNDAY -> "DOMINGO";
        };
    }

    private String mapaDeTresAsientos() {
        ObjectNode mapa = objectMapper.createObjectNode();
        ObjectNode piso = mapa.putArray("pisos").addObject().put("piso", 1).put("filas", 2).put("columnas", 3);
        ArrayNode celdas = piso.putArray("celdas");
        celdas.addObject().put("fila", 0).put("columna", 1).put("tipo", "CONDUCTOR");
        celdas.addObject().put("fila", 1).put("columna", 1).put("tipo", "ASIENTO").put("asiento", "1");
        celdas.addObject().put("fila", 1).put("columna", 2).put("tipo", "ASIENTO").put("asiento", "2");
        celdas.addObject().put("fila", 1).put("columna", 3).put("tipo", "ASIENTO").put("asiento", "3");
        return mapa.toString();
    }

    private static Instant mananaA(int hora, int minuto) {
        return LocalDate.now(CatalogTime.ZONE).plusDays(1).atTime(LocalTime.of(hora, minuto))
                .atZone(CatalogTime.ZONE).toInstant();
    }

    private static String ciudad(String prefijo) {
        return prefijo + " RV" + SECUENCIA.incrementAndGet();
    }

    /** Eventos del stream cuyo payload tiene campo == valor, en orden de publicación. */
    private List<JsonNode> eventos(String campo, String valor) throws Exception {
        List<JsonNode> eventos = new ArrayList<>();
        for (var r : redisTemplate.opsForStream().range(stream, Range.unbounded())) {
            JsonNode payload = objectMapper.readTree(String.valueOf(r.getValue().get("payload")));
            if (valor.equals(payload.path(campo).asText())) {
                ObjectNode e = objectMapper.createObjectNode().put("type", String.valueOf(r.getValue().get("type")));
                e.set("payload", payload);
                eventos.add(e);
            }
        }
        return eventos;
    }

    private JsonNode json(org.springframework.test.web.servlet.MvcResult result) throws Exception {
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
