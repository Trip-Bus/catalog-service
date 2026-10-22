package com.tripbus.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.repository.BusRepository;
import com.tripbus.catalog.repository.CompanyRepository;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.seed.SeedService;
import com.tripbus.catalog.service.CatalogTime;
import com.tripbus.catalog.service.SeatLayoutFactory;
import com.tripbus.catalog.web.dto.SeedResult;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-100, US-110 y US-111 contra PostgreSQL 16 y Redis 7 reales (Testcontainers, requiere Docker):
 * empresas, buses, mapa de asientos, efecto de inactivar una empresa en la búsqueda y endpoint interno.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestPropertySource(properties = {"tripbus.seed.on-startup=false", "SEAT_INVENTORY_URL=http://localhost:1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class EmpresasBusesIT {

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
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private BusRepository busRepository;
    @Autowired
    private ItineraryRepository itineraryRepository;
    @Value("${tripbus.internal-token}")
    private String internalToken;
    @Value("${tripbus.events.stream}")
    private String stream;

    @BeforeAll
    void sembrar() {
        seedService.seed();
    }

    // ---------------------------------------------------------------- US-100 · empresas

    @Test
    void elSeedSigueIdempotenteConEmpresasActivasYBusesActivos() throws Exception {
        SeedResult segunda = seedService.seed();
        assertThat(segunda.itinerariosNuevos()).isZero();
        assertThat(segunda.buses()).isZero();

        UUID andino = SeedService.id("company:andino");
        mockMvc.perform(get("/companies/{id}", andino).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Expreso Andino"))
                .andExpect(jsonPath("$.estado").value("ACTIVA"))
                .andExpect(jsonPath("$.creadoEn").isNotEmpty());
        mockMvc.perform(get("/buses").param("companyId", andino.toString()).param("size", "1").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].estado").value("ACTIVO"))
                .andExpect(jsonPath("$.totalElements").value(busRepository.findAll().stream()
                        .filter(b -> b.getCompanyId().equals(andino)).count()));
    }

    @Test
    void crudDeEmpresasConValidacionesYEventos() throws Exception {
        String nit = nitUnico();
        JsonNode creada = json(mockMvc.perform(post("/companies").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("nombre", "  Rápido Ocaña  ", "nit", nit + "-7")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value("Rápido Ocaña"))
                .andExpect(jsonPath("$.nit").value(nit + "-7"))
                .andExpect(jsonPath("$.estado").value("ACTIVA"))
                .andReturn().getResponse().getContentAsString());
        String id = creada.get("id").asText();

        // El NIT sin dígito de verificación es el mismo NIT.
        mockMvc.perform(post("/companies").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("nombre", "Copia", "nit", nit)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NIT_ALREADY_EXISTS"));
        mockMvc.perform(post("/companies").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("nombre", " ", "nit", "90012-3")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.nombre").exists())
                .andExpect(jsonPath("$.error.details.nit").exists());

        mockMvc.perform(put("/companies/{id}", id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("nombre", "Rápido Ocaña SAS", "nit", nit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Rápido Ocaña SAS"))
                .andExpect(jsonPath("$.nit").value(nit));
        // Un NIT de otra empresa (el del seed) no se puede tomar.
        mockMvc.perform(put("/companies/{id}", id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("nombre", "Rápido Ocaña SAS", "nit", "900100001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NIT_ALREADY_EXISTS"));

        mockMvc.perform(patch("/companies/{id}/estado", id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("estado", "INACTIVA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("INACTIVA"));
        mockMvc.perform(patch("/companies/{id}/estado", id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("estado", "BORRADA")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/companies").param("estado", "INACTIVA").param("size", "100").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.estado == 'ACTIVA')]").isEmpty());
        mockMvc.perform(get("/companies").param("size", "1").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(companyRepository.count()));
        mockMvc.perform(get("/companies").param("size", "500").with(admin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/companies/{id}", UUID.randomUUID()).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMPANY_NOT_FOUND"));

        // Eventos publicados en el stream (mismo publisher de hoy).
        List<String> tipos = redisTemplate.opsForStream().range(stream, Range.unbounded()).stream()
                .filter(r -> String.valueOf(r.getValue().get("payload")).contains(id))
                .map(r -> String.valueOf(r.getValue().get("type")))
                .toList();
        assertThat(tipos).containsExactly("CompanyCreated", "CompanyUpdated", "CompanyDeactivated");
    }

    @Test
    void empresasSoloParaAdministradores() throws Exception {
        mockMvc.perform(get("/companies")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/companies").with(operador(SeedService.id("company:andino"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/companies").with(operador(SeedService.id("company:andino")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("nombre", "X", "nit", nitUnico())))
                .andExpect(status().isForbidden());
    }

    @Test
    void empresaInactivaDesapareceDeLaBusquedaYDeViajesEnRutaYNoCreaBuses() throws Exception {
        UUID companyId = crearEmpresa();
        Company empresa = companyRepository.findById(companyId).orElseThrow();
        Route ruta = routeRepository.save(new Route(UUID.randomUUID(), empresa, "Pasto IT", "Ipiales IT", 120));
        UUID busId = crearBus(companyId, "UN_PISO");
        LocalDate manana = LocalDate.now(CatalogTime.ZONE).plusDays(1);
        Instant salida = manana.atTime(LocalTime.NOON).atZone(CatalogTime.ZONE).toInstant();
        Instant now = Instant.now();
        itineraryRepository.saveAll(List.of(
                itinerario(ruta, busId, salida, salida.plus(2, ChronoUnit.HOURS)),
                itinerario(ruta, busId, now.minus(30, ChronoUnit.MINUTES), now.plus(90, ChronoUnit.MINUTES))));

        // Primera búsqueda: queda en la cache de Redis.
        buscar(manana).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/trips/in-progress").param("origin", "pasto it"))
                .andExpect(jsonPath("$.length()").value(1));

        cambiarEstado(companyId, "INACTIVA");
        // La cache se invalidó: la búsqueda ya no la muestra aunque estaba cacheada.
        buscar(manana).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/trips/in-progress").param("origin", "pasto it"))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/buses").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("placa", placaUnica(), "tipo", "UN_PISO", "companyId", companyId.toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMPANY_INACTIVE"));

        cambiarEstado(companyId, "ACTIVA");
        buscar(manana).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void endpointInternoDeEmpresasConToken() throws Exception {
        UUID andino = SeedService.id("company:andino");
        mockMvc.perform(get("/internal/companies/{id}", andino)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/companies/{id}", andino).header("X-Internal-Token", "otro"))
                .andExpect(status().isUnauthorized());
        // Un JWT de usuario no sirve para /internal/**.
        mockMvc.perform(get("/internal/companies/{id}", andino).with(admin())).andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/companies/{id}", andino).header("X-Internal-Token", internalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(andino.toString()))
                .andExpect(jsonPath("$.nombre").value("Expreso Andino"))
                .andExpect(jsonPath("$.estado").value("ACTIVA"))
                .andExpect(jsonPath("$.nit").doesNotExist());
        mockMvc.perform(get("/internal/companies/{id}", UUID.randomUUID()).header("X-Internal-Token", internalToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMPANY_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- US-110 · buses

    @Test
    void adminCreaBusesDeCualquierEmpresaConPlacaValidaYUnica() throws Exception {
        UUID companyId = crearEmpresa();
        String placa = placaUnica();
        mockMvc.perform(post("/buses").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("placa", placa, "tipo", "UN_PISO")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMPANY_REQUIRED"));
        mockMvc.perform(post("/buses").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("placa", placa, "tipo", "UN_PISO", "companyId", UUID.randomUUID().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMPANY_NOT_FOUND"));
        mockMvc.perform(post("/buses").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("placa", "AB1234", "tipo", "TRES_PISOS", "companyId", companyId.toString())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/buses").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("placa", "AB1234", "tipo", "UN_PISO", "companyId", companyId.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.placa").exists());

        mockMvc.perform(post("/buses").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("placa", placa.toLowerCase(), "tipo", "DOS_PISOS",
                                "companyId", companyId.toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.placa").value(placa))
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.capacidad").value(60))
                .andExpect(jsonPath("$.estado").value("ACTIVO"));
        mockMvc.perform(post("/buses").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("placa", placa, "tipo", "UN_PISO", "companyId", companyId.toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PLACA_ALREADY_EXISTS"));
    }

    @Test
    void operadorSoloGestionaLosBusesDeSuEmpresa() throws Exception {
        UUID mia = crearEmpresa();
        UUID otra = crearEmpresa();
        UUID busOtra = crearBus(otra, "UN_PISO");

        // El operador crea en su empresa sin enviar companyId; no puede crear en otra.
        JsonNode bus = json(mockMvc.perform(post("/buses").with(operador(mia)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("placa", placaUnica(), "tipo", "UN_PISO")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyId").value(mia.toString()))
                .andExpect(jsonPath("$.capacidad").value(40))
                .andReturn().getResponse().getContentAsString());
        String busId = bus.get("id").asText();
        mockMvc.perform(post("/buses").with(operador(mia)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("placa", placaUnica(), "tipo", "UN_PISO", "companyId", otra.toString())))
                .andExpect(status().isForbidden());

        // Un bus de otra empresa es 404 para todo.
        mockMvc.perform(get("/buses/{id}", busOtra).with(operador(mia))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUS_NOT_FOUND"));
        mockMvc.perform(put("/buses/{id}", busOtra).with(operador(mia)).contentType(MediaType.APPLICATION_JSON)
                .content(body("placa", placaUnica(), "tipo", "UN_PISO"))).andExpect(status().isNotFound());
        mockMvc.perform(patch("/buses/{id}/estado", busOtra).with(operador(mia)).contentType(MediaType.APPLICATION_JSON)
                .content(body("estado", "INACTIVO"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/buses/{id}/seatmap", busOtra).with(operador(mia))).andExpect(status().isNotFound());
        mockMvc.perform(get("/buses/{id}", busOtra).with(admin())).andExpect(status().isOk());

        // El listado solo trae los suyos aunque pida otra empresa.
        mockMvc.perform(get("/buses").param("companyId", otra.toString()).with(operador(mia)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(busId));

        mockMvc.perform(get("/buses/{id}", busId).with(operador(mia)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("UN_PISO"));
    }

    @Test
    void editarCambiarEstadoYFiltrarBuses() throws Exception {
        UUID companyId = crearEmpresa();
        UUID uno = crearBus(companyId, "UN_PISO");
        UUID dos = crearBus(companyId, "DOS_PISOS");
        String placaNueva = placaUnica();

        mockMvc.perform(put("/buses/{id}", uno).with(operador(companyId)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("placa", placaNueva, "tipo", "UN_PISO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placa").value(placaNueva))
                .andExpect(jsonPath("$.capacidad").value(40));
        String placaDos = busRepository.findById(dos).orElseThrow().getPlaca();
        mockMvc.perform(put("/buses/{id}", uno).with(operador(companyId)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("placa", placaDos, "tipo", "UN_PISO")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PLACA_ALREADY_EXISTS"));
        // Cambiar el tipo sin viajes futuros reinicia el mapa al del nuevo tipo.
        mockMvc.perform(put("/buses/{id}", uno).with(operador(companyId)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("placa", placaNueva, "tipo", "DOS_PISOS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("DOS_PISOS"))
                .andExpect(jsonPath("$.capacidad").value(60));
        mockMvc.perform(get("/buses/{id}/seatmap", uno).with(operador(companyId)))
                .andExpect(jsonPath("$.pisos.length()").value(2));

        mockMvc.perform(patch("/buses/{id}/estado", dos).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("estado", "MANTENIMIENTO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("MANTENIMIENTO"));
        mockMvc.perform(patch("/buses/{id}/estado", dos).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("estado", "MANTENIMIENTO")))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/buses/{id}/estado", dos).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.estado").exists());

        mockMvc.perform(get("/buses").with(operador(companyId)).param("estado", "MANTENIMIENTO"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(dos.toString()));
        mockMvc.perform(get("/buses").with(operador(companyId)).param("tipo", "DOS_PISOS").param("estado", "ACTIVO"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(uno.toString()));
        mockMvc.perform(get("/buses").with(operador(companyId)).param("tipo", "UN_PISO"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/buses").with(admin()).param("estado", "NUEVO"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void busesExigenRolYEmpresaEnElToken() throws Exception {
        mockMvc.perform(get("/buses")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/buses").with(usuario("PASAJERO", null))).andExpect(status().isForbidden());
        mockMvc.perform(get("/buses").with(usuario("OPERADOR", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ---------------------------------------------------------------- US-111 · mapa de asientos

    @Test
    void mapaPorDefectoTieneElMismoFormatoQueSeatLayoutFactory() throws Exception {
        UUID companyId = crearEmpresa();
        for (BusType tipo : BusType.values()) {
            UUID busId = crearBus(companyId, tipo.name());
            JsonNode mapa = json(mockMvc.perform(get("/buses/{id}/seatmap", busId).with(operador(companyId)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertThat(mapa).isEqualTo(objectMapper.valueToTree(SeatLayoutFactory.layout(tipo)));
        }
        // El de un bus del seed (con viajes) es el mismo que ve /itineraries/{id}/seatmap.
        UUID busSeed = SeedService.id("bus:bog-med:0");
        mockMvc.perform(get("/buses/{id}/seatmap", busSeed).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("DOS_PISOS"))
                .andExpect(jsonPath("$.capacidad").value(60));
    }

    @Test
    void reemplazarElMapaRecalculaLaCapacidadYLoVeElItinerario() throws Exception {
        UUID companyId = crearEmpresa();
        UUID busId = crearBus(companyId, "UN_PISO");
        Company empresa = companyRepository.findById(companyId).orElseThrow();
        Route ruta = routeRepository.save(new Route(UUID.randomUUID(), empresa, "Neiva IT", "Pitalito IT", 180));
        Instant now = Instant.now();
        Itinerary pasado = itinerario(ruta, busId, now.minus(2, ChronoUnit.DAYS), now.minus(47, ChronoUnit.HOURS));
        Itinerary canceladoFuturo = itinerario(ruta, busId, now.plus(1, ChronoUnit.DAYS),
                now.plus(1, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS));
        canceladoFuturo.cancelar();
        itineraryRepository.saveAll(List.of(pasado, canceladoFuturo));

        ObjectNode mapa = mapaPequeno();
        mockMvc.perform(put("/buses/{id}/seatmap", busId).with(operador(companyId))
                        .contentType(MediaType.APPLICATION_JSON).content(mapa.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("UN_PISO"))
                .andExpect(jsonPath("$.capacidad").value(3))
                .andExpect(jsonPath("$.pisos[0].celdas.length()").value(5));
        mockMvc.perform(get("/buses/{id}", busId).with(operador(companyId)))
                .andExpect(jsonPath("$.capacidad").value(3));
        mockMvc.perform(get("/itineraries/{id}/seatmap", pasado.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacidad").value(3))
                .andExpect(jsonPath("$.asientos.length()").value(3))
                .andExpect(jsonPath("$.distribucion.pisos[0].filas").value(2));

        // Mapa inválido: 400 INVALID_SEATMAP.
        ObjectNode repetido = mapaPequeno();
        ((ObjectNode) repetido.get("pisos").get(0).get("celdas").get(4)).put("asiento", "1");
        mockMvc.perform(put("/buses/{id}/seatmap", busId).with(operador(companyId))
                        .contentType(MediaType.APPLICATION_JSON).content(repetido.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SEATMAP"));
    }

    @Test
    void crearBusConMapaPropio() throws Exception {
        UUID companyId = crearEmpresa();
        ObjectNode request = objectMapper.createObjectNode()
                .put("placa", placaUnica()).put("tipo", "UN_PISO").put("companyId", companyId.toString());
        request.set("distribucion", mapaPequeno());
        mockMvc.perform(post("/buses").with(admin()).contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacidad").value(3));

        request.put("placa", placaUnica()).put("tipo", "DOS_PISOS");
        mockMvc.perform(post("/buses").with(admin()).contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SEATMAP"));
    }

    @Test
    void busConViajesFuturosNoCambiaDeMapaNiDeTipo() throws Exception {
        // Bus del seed: siempre tiene viajes programados en los próximos días.
        UUID busSeed = SeedService.id("bus:bog-cal:0");
        mockMvc.perform(put("/buses/{id}/seatmap", busSeed).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(mapaPequeno().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUS_IN_USE"));
        String placa = busRepository.findById(busSeed).orElseThrow().getPlaca();
        mockMvc.perform(put("/buses/{id}", busSeed).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("placa", placa, "tipo", "DOS_PISOS")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUS_IN_USE"));
        mockMvc.perform(get("/buses/{id}", busSeed).with(admin()))
                .andExpect(jsonPath("$.capacidad").value(40))
                .andExpect(jsonPath("$.tipo").value("UN_PISO"));
    }

    // ---------------------------------------------------------------- utilidades

    /** 1 piso, 2 filas x 3 columnas: conductor, pasillo y 3 asientos. */
    private ObjectNode mapaPequeno() {
        ObjectNode mapa = objectMapper.createObjectNode();
        ObjectNode piso = mapa.putArray("pisos").addObject().put("piso", 1).put("filas", 2).put("columnas", 3);
        var celdas = piso.putArray("celdas");
        celdas.addObject().put("fila", 0).put("columna", 1).put("tipo", "CONDUCTOR");
        celdas.addObject().put("fila", 1).put("columna", 1).put("tipo", "ASIENTO").put("asiento", "1");
        celdas.addObject().put("fila", 1).put("columna", 2).put("tipo", "PASILLO");
        celdas.addObject().put("fila", 0).put("columna", 3).put("tipo", "ASIENTO").put("asiento", "2");
        celdas.addObject().put("fila", 1).put("columna", 3).put("tipo", "ASIENTO").put("asiento", "3");
        return mapa;
    }

    private org.springframework.test.web.servlet.ResultActions buscar(LocalDate fecha) throws Exception {
        return mockMvc.perform(get("/trips/search")
                        .param("origin", "Pasto IT").param("destination", "Ipiales IT").param("date", fecha.toString()))
                .andExpect(status().isOk());
    }

    private void cambiarEstado(UUID companyId, String estado) throws Exception {
        mockMvc.perform(patch("/companies/{id}/estado", companyId).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body("estado", estado)))
                .andExpect(status().isOk());
    }

    private UUID crearEmpresa() throws Exception {
        String body = mockMvc.perform(post("/companies").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("nombre", "Empresa IT " + SECUENCIA.incrementAndGet(), "nit", nitUnico())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json(body).get("id").asText());
    }

    private UUID crearBus(UUID companyId, String tipo) throws Exception {
        MockHttpServletRequestBuilder request = post("/buses").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("placa", placaUnica(), "tipo", tipo, "companyId", companyId.toString()));
        String body = mockMvc.perform(request).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json(body).get("id").asText());
    }

    private Itinerary itinerario(Route ruta, UUID busId, Instant salida, Instant llegada) {
        return new Itinerary(UUID.randomUUID(), ruta, busRepository.findById(busId).orElseThrow(), salida, llegada,
                new BigDecimal("50000"));
    }

    private String body(String... pares) {
        ObjectNode node = objectMapper.createObjectNode();
        for (int i = 0; i < pares.length; i += 2) {
            node.put(pares[i], pares[i + 1]);
        }
        return node.toString();
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    /** Placas QZA000, QZA001...: nunca chocan con las del seed (empiezan por T). */
    private static String placaUnica() {
        int n = SECUENCIA.incrementAndGet();
        return "QZ" + (char) ('A' + (n / 1000) % 26) + String.format("%03d", n % 1000);
    }

    private static String nitUnico() {
        return String.format("8%08d", SECUENCIA.incrementAndGet());
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
