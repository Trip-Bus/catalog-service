package com.tripbus.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.catalog.common.events.EventEnvelope;
import com.tripbus.catalog.events.OfferEventsHandler;
import com.tripbus.catalog.seed.SeedService;
import com.tripbus.catalog.service.CatalogTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Precios consistentes contra PostgreSQL y Redis reales con el seed: el precio "desde" de la búsqueda es el total de
 * un adulto en GET /itineraries/{id}/price (con temporada, oferta y tramo), el calendario coincide día a día con
 * /trips/search con los mismos filtros, las facetas, y las empresas de las terminales de Bogotá. Seat Inventory
 * apunta a un puerto cerrado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestPropertySource(properties = {"tripbus.seed.on-startup=false", "SEAT_INVENTORY_URL=http://localhost:1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class PreciosConsistentesIT {

    private static final UUID ANDINO = SeedService.id("company:andino");
    private static final UUID RAPIDO_OCHOA = SeedService.id("company:rapido-ochoa");
    private static final UUID CARIBE = SeedService.id("company:caribe");
    private static final String NORTE = "Terminal del Norte";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SeedService seedService;
    @Autowired
    private OfferEventsHandler offerEventsHandler;
    @Autowired
    private TransactionTemplate tx;

    private LocalDate manana;

    @BeforeAll
    void sembrar() throws Exception {
        seedService.seed();
        LocalDate hoy = LocalDate.now(CatalogTime.ZONE);
        manana = hoy.plusDays(1);
        // Temporada de toda la empresa Rápido Ochoa (1,15) y de la ruta bog-med de Andino (1,2) en los próximos días.
        temporada(RAPIDO_OCHOA, null, hoy, hoy.plusDays(4), "1.15");
        temporada(ANDINO, SeedService.id("route:bog-med"), manana, manana.plusDays(1), "1.2");
        // Una oferta activa en un viaje de Andino de mañana.
        UUID conOferta = UUID.fromString(buscar("Bogotá", "Medellín", manana, Map.of("companyId", ANDINO.toString()))
                .get(1).get("itineraryId").asText());
        var activada = objectMapper.createObjectNode().put("offerId", UUID.randomUUID().toString())
                .put("itineraryId", conOferta.toString()).put("precioOferta", 61900)
                .put("fin", Instant.now().plus(3, ChronoUnit.DAYS).toString());
        tx.executeWithoutResult(s -> offerEventsHandler.handle(new EventEnvelope(UUID.randomUUID().toString(),
                "OfferActivated", "test", Instant.now(), activada)));
    }

    @Test
    void elPrecioDesdeEsElTotalDeUnAdultoSinRecargoEnElDesglose() throws Exception {
        JsonNode r = buscar("Bogotá", "Medellín", manana, Map.of("tramos", "true"));

        assertThat(r.size()).isGreaterThan(10);
        boolean conTemporada = false;
        boolean conOferta = false;
        boolean tramo = false;
        for (JsonNode viaje : r) {
            MockHttpServletRequestBuilder precio = get("/itineraries/{id}/price", viaje.get("itineraryId").asText())
                    .param("tipos", "ADULTO");
            if (viaje.has("fromOrden")) {
                precio.param("from", viaje.get("fromOrden").asText()).param("to", viaje.get("toOrden").asText());
                tramo = true;
            }
            JsonNode d = json(mockMvc.perform(precio).andExpect(status().isOk()).andReturn().getResponse())
                    .get("desglose");
            assertThat(viaje.get("precioDesde").decimalValue()).as(viaje.toString())
                    .isEqualByComparingTo(d.get("total").decimalValue());
            conTemporada |= !d.get("temporada").isNull();
            conOferta |= d.get("conOferta").asBoolean();
        }
        assertThat(conTemporada).isTrue();
        assertThat(conOferta).isTrue();
        assertThat(tramo).isTrue();
    }

    @Test
    void elOrdenYElFiltroDePrecioUsanElPrecioDesde() throws Exception {
        JsonNode r = buscar("Bogotá", "Medellín", manana, Map.of("sort", "precio"));
        List<BigDecimal> precios = new ArrayList<>();
        r.forEach(v -> precios.add(v.get("precioDesde").decimalValue()));
        assertThat(precios).isSortedAccordingTo(BigDecimal::compareTo);

        BigDecimal tope = precios.get(precios.size() / 2);
        JsonNode baratos = buscar("Bogotá", "Medellín", manana, Map.of("precioMax", tope.toPlainString()));
        assertThat(baratos.size()).isPositive().isLessThan(r.size());
        baratos.forEach(v -> assertThat(v.get("precioDesde").decimalValue()).isLessThanOrEqualTo(tope));
    }

    /** Para cada día y filtros, precioMinimo y viajes del calendario coinciden con /trips/search de ese día. */
    @Test
    void cadaDiaDelCalendarioCoincideConLaBusquedaDeEseDia() throws Exception {
        LocalDate hoy = LocalDate.now(CatalogTime.ZONE);
        List<Map<String, String>> combinaciones = List.of(Map.of(),
                Map.of("companyId", RAPIDO_OCHOA.toString()),
                Map.of("franja", "NOCHE"),
                Map.of("tipoBus", "DOS_PISOS", "franja", "MANANA"),
                Map.of("tramos", "true", "franja", "TARDE"),
                Map.of("tramos", "false", "companyId", ANDINO.toString()));
        for (Map<String, String> filtros : combinaciones) {
            MockHttpServletRequestBuilder calendario = get("/trips/calendar").param("origin", "Bogotá")
                    .param("destination", "Medellín").param("from", hoy.toString()).param("days", "5");
            filtros.forEach(calendario::param);
            JsonNode dias = json(mockMvc.perform(calendario).andExpect(status().isOk()).andReturn().getResponse());
            assertThat(dias.size()).isEqualTo(5);
            for (JsonNode dia : dias) {
                LocalDate fecha = LocalDate.parse(dia.get("fecha").asText());
                Map<String, String> conOrden = new java.util.HashMap<>(filtros);
                conOrden.put("sort", "precio");
                conOrden.put("size", "100");
                MockHttpServletResponse respuesta = buscarRespuesta("Bogotá", "Medellín", fecha, conOrden);
                JsonNode busqueda = json(respuesta);
                assertThat(dia.get("viajes").asInt()).as(fecha + " " + filtros)
                        .isEqualTo(Integer.parseInt(respuesta.getHeader("X-Total-Count")));
                BigDecimal minimo = null;
                for (JsonNode v : busqueda) {
                    if (!v.get("agotado").asBoolean(false)) {
                        BigDecimal p = v.get("precioDesde").decimalValue();
                        minimo = minimo == null || p.compareTo(minimo) < 0 ? p : minimo;
                    }
                }
                if (minimo == null) {
                    assertThat(dia.get("precioMinimo").isNull()).as(fecha + " " + filtros).isTrue();
                } else {
                    assertThat(dia.get("precioMinimo").decimalValue()).as(fecha + " " + filtros)
                            .isEqualByComparingTo(minimo);
                }
            }
        }
    }

    @Test
    void lasFacetasDanTodasLasEmpresasDeLaRutaConSuTerminalYElRangoDePrecios() throws Exception {
        JsonNode f = facetas("Bogotá", "Tunja", manana, Map.of());

        List<String> nombres = new ArrayList<>();
        f.get("empresas").forEach(e -> nombres.add(e.get("nombre").asText()));
        assertThat(nombres).isSorted().contains("Autoboy", "Coflonorte (Libertadores)", "Flota Boyacá",
                "Transportes Caribe Sol");
        for (JsonNode e : f.get("empresas")) {
            if (e.get("nombre").asText().equals("Transportes Caribe Sol")) {
                assertThat(e.get("terminal").isNull()).isTrue();
                assertThat(e.get("viajes").asInt()).isEqualTo(5);
            } else {
                assertThat(e.get("terminal").asText()).as(e.toString()).isEqualTo(NORTE);
                assertThat(e.get("viajes").asInt()).as(e.toString()).isEqualTo(2);
            }
        }
        JsonNode r = buscar("Bogotá", "Tunja", manana, Map.of("sort", "precio"));
        assertThat(f.get("precioMin").decimalValue()).isEqualByComparingTo(r.get(0).get("precioDesde").decimalValue());
        assertThat(f.get("precioMax").decimalValue())
                .isEqualByComparingTo(r.get(r.size() - 1).get("precioDesde").decimalValue());
        assertThat(f.get("tiposBus").toString()).isEqualTo("[\"UN_PISO\"]");

        // Con un filtro de empresa el rango es el de esa empresa, pero siguen todas las empresas de la ruta.
        JsonNode caribe = facetas("Bogotá", "Tunja", manana, Map.of("companyId", CARIBE.toString(), "franja", "NOCHE"));
        assertThat(caribe.get("empresas").size()).isEqualTo(f.get("empresas").size());
        assertThat(caribe.get("precioMin").decimalValue()).isEqualByComparingTo("25200");
    }

    @Test
    void enUnDiaSinViajesLasFacetasSiguenMostrandoLasEmpresasDeLaRuta() throws Exception {
        // Más allá de la ventana del seed no hay viajes, pero sí empresas que cubren la ruta en los próximos 14 días.
        JsonNode f = facetas("Bogotá", "Pereira", LocalDate.now(CatalogTime.ZONE).plusDays(40), Map.of());

        assertThat(f.get("precioMin").isNull()).isTrue();
        assertThat(f.get("tiposBus").size()).isZero();
        List<String> nombres = new ArrayList<>();
        f.get("empresas").forEach(e -> {
            nombres.add(e.get("nombre").asText());
            assertThat(e.get("viajes").asInt()).isZero();
        });
        assertThat(nombres).contains("Velotax");
        mockMvc.perform(get("/trips/search/facets").param("origin", "Bogotá").param("destination", "Pereira")
                .param("date", manana.toString()).param("tipoBus", "AVION")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SEARCH"));
    }

    @Test
    void elSeedTraeLasEmpresasDeLasTerminalesDeBogotaConSuTerminalEnLaPrimeraParada() throws Exception {
        mockMvc.perform(get("/routes/{id}/stops", SeedService.id("route:e:bolivariano:bog-cali")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("Bogotá"))
                .andExpect(jsonPath("$[0].terminalId").value("7e0a0001-0000-4000-8000-000000000001"));
        JsonNode r = buscar("Bogotá", "Cali", manana, Map.of("companyId",
                SeedService.id("company:bolivariano").toString()));
        assertThat(r.size()).isEqualTo(3);
        assertThat(r.get(0).get("empresa").asText()).isEqualTo("Expreso Bolivariano");
    }

    private void temporada(UUID companyId, UUID routeId, LocalDate desde, LocalDate hasta, String multiplicador)
            throws Exception {
        String ruta = routeId == null ? "" : "\"routeId\":\"" + routeId + "\",";
        mockMvc.perform(post("/fares/seasons").with(operador(companyId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{%s\"nombre\":\"Alta %s\",\"desde\":\"%s\",\"hasta\":\"%s\",\"multiplicador\":%s}"
                                .formatted(ruta, multiplicador, desde, hasta, multiplicador)))
                .andExpect(status().isCreated());
    }

    private JsonNode facetas(String origen, String destino, LocalDate fecha, Map<String, String> filtros)
            throws Exception {
        MockHttpServletRequestBuilder req = get("/trips/search/facets").param("origin", origen)
                .param("destination", destino).param("date", fecha.toString());
        filtros.forEach(req::param);
        return json(mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse());
    }

    private JsonNode buscar(String origen, String destino, LocalDate fecha, Map<String, String> filtros)
            throws Exception {
        return json(buscarRespuesta(origen, destino, fecha, filtros));
    }

    private MockHttpServletResponse buscarRespuesta(String origen, String destino, LocalDate fecha,
                                                    Map<String, String> filtros) throws Exception {
        MockHttpServletRequestBuilder req = get("/trips/search").param("origin", origen).param("destination", destino)
                .param("date", fecha.toString());
        filtros.forEach(req::param);
        return mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse();
    }

    private JsonNode json(MockHttpServletResponse respuesta) throws Exception {
        return objectMapper.readTree(respuesta.getContentAsString());
    }

    private static RequestPostProcessor operador(UUID companyId) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "OPERADOR")
                        .claim("companyId", companyId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"));
    }
}
