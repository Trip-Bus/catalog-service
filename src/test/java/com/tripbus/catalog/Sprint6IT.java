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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 6 contra PostgreSQL y Redis reales (Testcontainers) con el seed: búsqueda igual a la de antes sin
 * parámetros nuevos, búsqueda por tramo intermedio, precios (tramo, mínimo, exacto, oferta, temporada, tipo de
 * pasajero y categoría de asiento), calendario con Seat Inventory caído, ida y vuelta, terminales y autocompletado,
 * servicios del bus, categorías en ItineraryPublished y el interno de viajes. Seat Inventory apunta a un puerto
 * cerrado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestPropertySource(properties = {"tripbus.seed.on-startup=false", "SEAT_INVENTORY_URL=http://localhost:1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class Sprint6IT {

    private static final UUID ANDINO = SeedService.id("company:andino");
    private static final UUID CORDILLERA = SeedService.id("company:cordillera");
    private static final UUID CARIBE = SeedService.id("company:caribe");
    private static final UUID SALITRE = UUID.fromString("7e0a0001-0000-4000-8000-000000000001");

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
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Value("${tripbus.events.stream}")
    private String stream;
    @Value("${tripbus.internal-token}")
    private String internalToken;

    private LocalDate manana;

    @BeforeAll
    void sembrar() {
        seedService.seed();
        manana = LocalDate.now(CatalogTime.ZONE).plusDays(1);
    }

    // ---------------------------------------------------------------- búsqueda

    @Test
    void sinParametrosNuevosLaBusquedaEsLaDeAntesMasLosServicios() throws Exception {
        JsonNode r = json(mockMvc.perform(get("/trips/search").param("origin", "Bogotá")
                .param("destination", "Medellín").param("date", manana.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5)));

        Set<String> campos = Set.of("itineraryId", "routeId", "busId", "empresa", "origen", "destino", "fechaSalida",
                "fechaLlegadaEstimada", "duracionMinutos", "precioBase", "moneda", "tipoBus", "capacidad",
                "asientosDisponibles", "agotado", "ofertaActiva", "disponibilidadConocida", "servicios");
        for (JsonNode viaje : r) {
            assertThat(nombres(viaje)).containsExactlyInAnyOrderElementsOf(campos);
            assertThat(viaje.get("origen").asText()).isEqualTo("Bogotá");
            // Tarifa del seed: 95.000, o 85.500 en la salida de las 22:00 (10 % de descuento).
            assertThat(viaje.get("precioBase").decimalValue().intValue()).isIn(95000, 85500);
            assertThat(viaje.get("servicios").isArray()).isTrue();
        }
    }

    @Test
    void sinRutaDirectaEncuentraLosTramosIntermediosConSuPrecio() throws Exception {
        JsonNode r = json(mockMvc.perform(get("/trips/search").param("origin", "honda")
                .param("destination", "MEDELLIN").param("date", manana.toString())).andExpect(status().isOk()));

        assertThat(r.size()).isGreaterThanOrEqualTo(5);
        JsonNode bogMed = null;
        for (JsonNode viaje : r) {
            assertThat(viaje.has("fromOrden")).isTrue();
            assertThat(viaje.has("salida") && viaje.has("llegada") && viaje.has("precio")).isTrue();
            if (viaje.get("routeId").asText().equals(SeedService.id("route:bog-med").toString())) {
                bogMed = viaje;
            }
        }
        assertThat(bogMed).isNotNull();
        // Honda (2, 210 min) → Medellín (4, 540 min): 95.000 × 330 / 540 = 58.055,56 → 58.100.
        assertThat(bogMed.get("fromOrden").asInt()).isEqualTo(2);
        assertThat(bogMed.get("toOrden").asInt()).isEqualTo(4);
        assertThat(bogMed.get("precio").decimalValue()).isEqualByComparingTo("58100");
        assertThat(Instant.parse(bogMed.get("salida").asText()))
                .isEqualTo(Instant.parse(bogMed.get("fechaSalida").asText()).plus(210, ChronoUnit.MINUTES));
    }

    @Test
    void conTramosTambienTraeLasRutasQuePasanPorLasDosCiudades() throws Exception {
        JsonNode r = json(mockMvc.perform(get("/trips/search").param("origin", "Bogotá")
                .param("destination", "Medellín").param("date", manana.toString()).param("tramos", "true"))
                .andExpect(status().isOk()));
        long tramos = 0;
        for (JsonNode viaje : r) {
            tramos += viaje.has("fromOrden") ? 1 : 0;
        }
        assertThat(r.size()).isGreaterThan(5);
        assertThat(tramos).isEqualTo(r.size() - 5L);
        mockMvc.perform(get("/trips/search").param("origin", "honda").param("destination", "Medellín")
                        .param("date", manana.toString()).param("tramos", "false"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void calendarioConSeatInventoryCaido() throws Exception {
        JsonNode dias = json(mockMvc.perform(get("/trips/calendar").param("origin", "Bogotá")
                .param("destination", "Medellín").param("from", manana.toString()).param("days", "3"))
                .andExpect(status().isOk()));

        assertThat(dias.size()).isEqualTo(3);
        for (JsonNode dia : dias) {
            assertThat(dia.get("viajes").asInt()).isEqualTo(5);
            // La salida de las 22:00 tiene 10 % de descuento en el seed: 85.500 es el mínimo de cada día.
            assertThat(dia.get("precioMinimo").decimalValue()).isEqualByComparingTo("85500");
            assertThat(dia.get("hayCupo").isNull()).isTrue();
        }
        mockMvc.perform(get("/trips/calendar").param("origin", "Bogotá").param("destination", "Medellín")
                .param("from", manana.toString()).param("days", "15")).andExpect(status().isBadRequest());
    }

    @Test
    void idaYVuelta() throws Exception {
        JsonNode r = json(mockMvc.perform(get("/trips/round-trip").param("origin", "Bogotá")
                .param("destination", "Medellín").param("date", manana.toString())
                .param("returnDate", manana.plusDays(2).toString())).andExpect(status().isOk()));

        assertThat(r.get("ida").size()).isEqualTo(5);
        assertThat(r.get("vuelta").size()).isEqualTo(5);
        assertThat(r.get("vuelta").get(0).get("origen").asText()).isEqualTo("Medellín");
        mockMvc.perform(get("/trips/round-trip").param("origin", "Bogotá").param("destination", "Medellín")
                        .param("date", manana.toString()).param("returnDate", manana.minusDays(1).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_SEARCH"));
    }

    // ---------------------------------------------------------------- precios

    @Test
    void sinParametrosElPrecioEsElDeSiempreYConTramoTraeElDesglose() throws Exception {
        UUID viaje = primerViaje("Bogotá", "Medellín");
        JsonNode simple = json(mockMvc.perform(get("/itineraries/{id}/price", viaje)).andExpect(status().isOk()));
        assertThat(simple.has("desglose")).isFalse();

        mockMvc.perform(get("/itineraries/{id}/price", viaje).param("from", "2"))
                .andExpect(jsonPath("$.desglose.fromOrden").value(2)).andExpect(jsonPath("$.desglose.toOrden").value(4));
        mockMvc.perform(get("/itineraries/{id}/price", viaje).param("to", "3"))
                .andExpect(jsonPath("$.desglose.fromOrden").value(1)).andExpect(jsonPath("$.desglose.toOrden").value(3))
                .andExpect(jsonPath("$.desglose.destino").value("Puerto Triunfo"));
        mockMvc.perform(get("/itineraries/{id}/price", viaje).param("from", "4").param("to", "2"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_PRICE_REQUEST"));
    }

    @Test
    void precioConTemporadaDeLaRutaYTipoDePasajero() throws Exception {
        UUID ruta = SeedService.id("route:bog-med");
        LocalDate hoy = LocalDate.now(CatalogTime.ZONE);
        mockMvc.perform(post("/fares/seasons").with(operador(ANDINO)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":"%s","nombre":"Puente","desde":"%s","hasta":"%s","multiplicador":1.2}"""
                                .formatted(ruta, hoy, hoy.plusDays(30))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.companyId").value(ANDINO.toString()));
        mockMvc.perform(put("/fares/passenger-types/NINO").with(operador(ANDINO))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"descuentoPorcentaje\":20}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/fares/passenger-types").with(operador(ANDINO)))
                .andExpect(jsonPath("$[1].tipo").value("NINO")).andExpect(jsonPath("$[1].descuentoPorcentaje").value(20));
        mockMvc.perform(get("/fares/seasons").with(admin()).param("companyId", ANDINO.toString()))
                .andExpect(jsonPath("$[0].nombre").value("Puente"));

        JsonNode d = json(mockMvc.perform(get("/itineraries/{id}/price", primerViaje("Bogotá", "Medellín"))
                .param("from", "2").param("to", "4").param("tipos", "ADULTO,NINO")).andExpect(status().isOk()))
                .get("desglose");

        // Honda → Medellín: 58.100; × 1,2 = 69.720 → 69.700; niño con 20 %: 55.760 → 55.800.
        assertThat(d.get("precioTramo").decimalValue()).isEqualByComparingTo("58100");
        assertThat(d.get("temporada").get("nombre").asText()).isEqualTo("Puente");
        assertThat(d.get("pasajeros").get(0).get("precio").decimalValue()).isEqualByComparingTo("69700");
        assertThat(d.get("pasajeros").get(1).get("precio").decimalValue()).isEqualByComparingTo("55800");
        assertThat(d.get("total").decimalValue()).isEqualByComparingTo("125500");
        // La temporada es de bog-med: el mismo tramo en otra ruta de la empresa no cambia.
        JsonNode vil = json(mockMvc.perform(get("/itineraries/{id}/price", primerViaje("Bogotá", "Villavicencio"))
                .param("tipos", "ADULTO")).andExpect(status().isOk())).get("desglose");
        assertThat(vil.get("temporada").isNull()).isTrue();
        mockMvc.perform(put("/fares/passenger-types/ADULTO").with(operador(ANDINO))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"descuentoPorcentaje\":5}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_FARE"));
    }

    @Test
    void elTramoNuncaValeMenosDelMinimo() throws Exception {
        // Bogotá (0) → Chipaque (45) de 180 minutos: 38.000 × 45 / 180 = 9.500, pero el 30 % es 11.400.
        JsonNode d = json(mockMvc.perform(get("/itineraries/{id}/price", primerViaje("Bogotá", "Villavicencio"))
                .param("from", "1").param("to", "2")).andExpect(status().isOk())).get("desglose");

        assertThat(d.get("precioTramo").decimalValue()).isEqualByComparingTo("11400");
        assertThat(d.get("destino").asText()).isEqualTo("Chipaque");
    }

    @Test
    void elPrecioExactoDelTramoSeUsaEnElDesgloseYEnLaBusqueda() throws Exception {
        UUID ruta = SeedService.id("route:med-ctg");
        mockMvc.perform(put("/routes/{id}/segment-prices", ruta).with(operador(CARIBE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"desdeOrden\":2,\"hastaOrden\":4,\"precio\":70000}]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].precio").value(70000));
        mockMvc.perform(get("/routes/{id}/segment-prices", ruta).with(operador(CARIBE)))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(put("/routes/{id}/segment-prices", ruta).with(operador(CARIBE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"desdeOrden\":1,\"hastaOrden\":4,\"precio\":1}]"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/routes/{id}/segment-prices", ruta).with(operador(ANDINO))).andExpect(status().isNotFound());

        JsonNode d = json(mockMvc.perform(get("/itineraries/{id}/price", primerViaje("Medellín", "Cartagena"))
                .param("from", "2").param("to", "4")).andExpect(status().isOk())).get("desglose");
        assertThat(d.get("precioTramo").decimalValue()).isEqualByComparingTo("70000");

        JsonNode r = json(mockMvc.perform(get("/trips/search").param("origin", "Caucasia")
                .param("destination", "Cartagena").param("date", manana.toString())).andExpect(status().isOk()));
        boolean conExacto = false;
        for (JsonNode viaje : r) {
            conExacto |= viaje.get("routeId").asText().equals(ruta.toString())
                    && viaje.get("precio").decimalValue().compareTo(new java.math.BigDecimal("70000")) == 0;
        }
        assertThat(conExacto).isTrue();
    }

    @Test
    void conOfertaElTramoSeCalculaSobreElPrecioDeLaOferta() throws Exception {
        UUID viaje = primerViaje("Bogotá", "Cali");
        var activada = objectMapper.createObjectNode().put("offerId", UUID.randomUUID().toString())
                .put("itineraryId", viaje.toString()).put("precioOferta", 60000)
                .put("fin", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        tx.executeWithoutResult(s -> offerEventsHandler.handle(new EventEnvelope(UUID.randomUUID().toString(),
                "OfferActivated", "test", Instant.now(), activada)));

        JsonNode d = json(mockMvc.perform(get("/itineraries/{id}/price", viaje).param("from", "2").param("to", "5"))
                .andExpect(status().isOk())).get("desglose");

        // Girardot (150) → Cali (600) de 600 minutos sobre los 60.000 de la oferta: 45.000.
        assertThat(d.get("conOferta").asBoolean()).isTrue();
        assertThat(d.get("precioTramo").decimalValue()).isEqualByComparingTo("45000");
    }

    @Test
    void internoCotizaConElRecargoDeLaCategoriaDelAsiento() throws Exception {
        mockMvc.perform(put("/fares/seat-surcharges/PREMIUM").with(operador(CARIBE))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recargo\":10000}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/fares/seat-surcharges").with(operador(CARIBE)))
                .andExpect(jsonPath("$[2].categoria").value("PREMIUM")).andExpect(jsonPath("$[2].recargo").value(10000));
        UUID viaje = primerViaje("Medellín", "Cartagena");

        mockMvc.perform(interno(post("/internal/pricing")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itineraryId":"%s","pasajeros":[{"tipo":"ADULTO","seatCategoria":"PREMIUM"},
                                {"tipo":"ADULTO","seatCategoria":"ESTANDAR"}]}""".formatted(viaje)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pasajeros[0].precio").value(150000))
                .andExpect(jsonPath("$.pasajeros[1].precio").value(140000))
                .andExpect(jsonPath("$.total").value(290000));
        mockMvc.perform(post("/internal/pricing").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
    }

    // ---------------------------------------------------------------- internos

    @Test
    void internoDeViajesYNitDeLaEmpresa() throws Exception {
        mockMvc.perform(interno(get("/internal/itineraries")).param("companyId", ANDINO.toString())
                        .param("routeId", SeedService.id("route:bog-med").toString())
                        .param("desde", manana.toString()).param("hasta", manana.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].capacidad").value(60))
                .andExpect(jsonPath("$[0].placa").isNotEmpty())
                .andExpect(jsonPath("$[0].precioBase").value(95000));
        JsonNode todos = json(mockMvc.perform(interno(get("/internal/itineraries"))
                .param("desde", manana.toString()).param("hasta", manana.plusDays(1).toString())).andExpect(status().isOk()));
        assertThat(todos.size()).isEqualTo(42 * 5 * 2);
        mockMvc.perform(interno(get("/internal/itineraries")).param("desde", manana.toString())
                .param("hasta", manana.plusDays(31).toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_RANGE"));
        mockMvc.perform(interno(get("/internal/itineraries")).param("desde", manana.toString())
                .param("hasta", manana.minusDays(1).toString())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/internal/itineraries").param("desde", manana.toString()).param("hasta", manana.toString()))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(interno(get("/internal/companies/{id}", ANDINO)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nit").value("900100001-1"));
    }

    // ---------------------------------------------------------------- terminales y autocompletado

    @Test
    void autocompletadoDeCiudadesYTerminalesSinTildes() throws Exception {
        mockMvc.perform(get("/places").param("q", "medell"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tipo").value("CIUDAD"))
                .andExpect(jsonPath("$[0].nombre").value("Medellín"))
                .andExpect(jsonPath("$[1].tipo").value("TERMINAL"))
                .andExpect(jsonPath("$[1].ciudad").value("Medellín"))
                .andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get("/places").param("q", "TERMINAL SAL"))
                .andExpect(jsonPath("$[0].nombre").value("Terminal Salitre"))
                .andExpect(jsonPath("$[0].terminalId").value(SALITRE.toString()));
        mockMvc.perform(get("/places")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/terminals").param("ciudad", "bogota")).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void terminalesDelAdminYParadasQueLasUsan() throws Exception {
        JsonNode creada = json(mockMvc.perform(post("/terminals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ciudad\":\"Zipaquirá\",\"nombre\":\"Terminal Zipa\",\"lat\":5.02,\"lng\":-74.0}"))
                .andExpect(status().isCreated()));
        UUID zipa = UUID.fromString(creada.get("id").asText());
        mockMvc.perform(post("/terminals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ciudad\":\"zipaquira\",\"nombre\":\"TERMINAL ZIPA\",\"lat\":5.02,\"lng\":-74.0}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/terminals").with(operador(ANDINO)).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        mockMvc.perform(put("/terminals/{id}", zipa).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ciudad\":\"Zipaquirá\",\"nombre\":\"Terminal de Zipaquirá\",\"lat\":5.02,\"lng\":-74.0}"))
                .andExpect(jsonPath("$.nombre").value("Terminal de Zipaquirá"));
        mockMvc.perform(get("/terminals/{id}", zipa)).andExpect(status().isOk());

        String paradas = """
                {"origen":"Bogotá","destino":"Zipaquirá","paradas":[
                  {"orden":1,"nombre":"Bogotá","lat":4.65,"lng":-74.11,"minutosDesdeSalida":0,"terminalId":"%s"},
                  {"orden":2,"nombre":"Zipaquirá","lat":5.02,"lng":-74.0,"minutosDesdeSalida":60,"terminalId":"%s"}]}""";
        mockMvc.perform(post("/routes").with(operador(ANDINO)).contentType(MediaType.APPLICATION_JSON)
                        .content(paradas.formatted(SALITRE, UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details['paradas[1].terminalId']").exists());
        JsonNode ruta = json(mockMvc.perform(post("/routes").with(operador(ANDINO))
                .contentType(MediaType.APPLICATION_JSON).content(paradas.formatted(SALITRE, zipa)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.paradas[1].terminalId").value(zipa.toString())));
        mockMvc.perform(delete("/terminals/{id}", zipa).with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TERMINAL_IN_USE"));

        // Quitar la terminal de una parada es un cambio de paradas; luego la terminal se puede borrar.
        mockMvc.perform(put("/routes/{id}/stops", ruta.get("id").asText()).with(operador(ANDINO))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                [{"orden":1,"nombre":"Bogotá","lat":4.65,"lng":-74.11,"minutosDesdeSalida":0},
                                 {"orden":2,"nombre":"Zipaquirá","lat":5.02,"lng":-74.0,"minutosDesdeSalida":60}]"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paradas[1].terminalId").doesNotExist());
        mockMvc.perform(delete("/terminals/{id}", zipa).with(admin())).andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- servicios y categorías

    @Test
    void losServiciosDelBusSalenEnLaBusquedaYEnElDetalle() throws Exception {
        UUID viaje = primerViaje("Bogotá", "Tunja");
        JsonNode detalle = json(mockMvc.perform(get("/itineraries/{id}", viaje)).andExpect(status().isOk()));
        UUID bus = UUID.fromString(detalle.get("busId").asText());
        String placa = detalle.get("placa").asText();

        mockMvc.perform(put("/buses/{id}", bus).with(operador(CARIBE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placa\":\"%s\",\"tipo\":\"UN_PISO\",\"servicios\":[\"TV\",\"USB\"]}".formatted(placa)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.servicios[0]").value("USB"))
                .andExpect(jsonPath("$.servicios[1]").value("TV"));

        mockMvc.perform(get("/itineraries/{id}", viaje)).andExpect(jsonPath("$.servicios.length()").value(2));
        JsonNode r = json(mockMvc.perform(get("/trips/search").param("origin", "Bogotá").param("destination", "Tunja")
                .param("date", manana.toString())));
        boolean visto = false;
        for (JsonNode v : r) {
            if (v.get("busId").asText().equals(bus.toString())) {
                visto = true;
                assertThat(v.get("servicios").toString()).isEqualTo("[\"USB\",\"TV\"]");
            }
        }
        assertThat(visto).isTrue();
        // Sin servicios en el PUT se conservan.
        mockMvc.perform(put("/buses/{id}", bus).with(operador(CARIBE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placa\":\"%s\",\"tipo\":\"UN_PISO\"}".formatted(placa)))
                .andExpect(jsonPath("$.servicios.length()").value(2));
    }

    @Test
    void lasCategoriasDelMapaVanEnItineraryPublished() throws Exception {
        String mapa = """
                {"pisos":[{"piso":1,"filas":2,"columnas":3,"celdas":[
                  {"fila":0,"columna":1,"tipo":"CONDUCTOR"},
                  {"fila":1,"columna":1,"tipo":"ASIENTO","asiento":"1","categoria":"PREMIUM"},
                  {"fila":1,"columna":2,"tipo":"PASILLO"},
                  {"fila":1,"columna":3,"tipo":"ASIENTO","asiento":"2"}]}]}""";
        JsonNode bus = json(mockMvc.perform(post("/buses").with(operador(CORDILLERA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placa\":\"CAT001\",\"tipo\":\"UN_PISO\",\"servicios\":[\"WIFI\"],\"distribucion\":%s}"
                                .formatted(mapa)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.capacidad").value(2))
                .andExpect(jsonPath("$.servicios[0]").value("WIFI")));
        mockMvc.perform(get("/buses/{id}/seatmap", bus.get("id").asText()).with(operador(CORDILLERA)))
                .andExpect(jsonPath("$.pisos[0].celdas[1].categoria").value("PREMIUM"))
                .andExpect(jsonPath("$.pisos[0].celdas[3].categoria").value("ESTANDAR"))
                .andExpect(jsonPath("$.pisos[0].celdas[2].categoria").doesNotExist());

        JsonNode viaje = json(mockMvc.perform(post("/itineraries").with(operador(CORDILLERA))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"routeId":"%s","busId":"%s","fechaSalida":"%s","precioBase":105000}"""
                                .formatted(SeedService.id("route:bog-cal"), bus.get("id").asText(),
                                        Instant.now().plus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES))))
                .andExpect(status().isCreated()));
        String itineraryId = viaje.get("itineraryId").asText();
        mockMvc.perform(get("/itineraries/{id}/seatmap", itineraryId))
                .andExpect(jsonPath("$.distribucion.pisos[0].celdas[1].categoria").value("PREMIUM"));

        JsonNode payload = null;
        // El seed deja miles de eventos en el stream: basta con leer los últimos (leerlo entero excede el timeout).
        for (var r : redisTemplate.opsForStream().reverseRange(stream, Range.unbounded(), Limit.limit().count(200))) {
            if ("ItineraryPublished".equals(r.getValue().get("type"))
                    && String.valueOf(r.getValue().get("payload")).contains(itineraryId)) {
                payload = objectMapper.readTree(String.valueOf(r.getValue().get("payload")));
            }
        }
        assertThat(payload).isNotNull();
        assertThat(payload.get("seatNumbers").toString()).isEqualTo("[\"1\",\"2\"]");
        assertThat(payload.get("seats").get(0).get("categoria").asText()).isEqualTo("PREMIUM");
        assertThat(payload.get("seats").get(1).get("categoria").asText()).isEqualTo("ESTANDAR");
    }

    // ---------------------------------------------------------------- utilidades

    private UUID primerViaje(String origen, String destino) throws Exception {
        JsonNode r = json(mockMvc.perform(get("/trips/search").param("origin", origen).param("destination", destino)
                .param("date", manana.toString()).param("tramos", "false")));
        return UUID.fromString(r.get(0).get("itineraryId").asText());
    }

    private JsonNode json(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString());
    }

    private static List<String> nombres(JsonNode nodo) {
        List<String> nombres = new ArrayList<>();
        Iterator<String> it = nodo.fieldNames();
        it.forEachRemaining(nombres::add);
        return nombres;
    }

    private MockHttpServletRequestBuilder interno(MockHttpServletRequestBuilder request) {
        return request.header("X-Internal-Token", internalToken);
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
