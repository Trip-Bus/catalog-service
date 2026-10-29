package com.tripbus.catalog.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sun.net.httpserver.HttpServer;
import com.tripbus.catalog.config.ServiceUrls;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * El cliente distingue "Seat Inventory respondió" (Optional con mapa) de "falló" (Optional vacío), y en el
 * remap (US-119) distingue éxito, conflicto de asientos y "no disponible".
 */
class SeatInventoryClientTest {

    private static final String TOKEN = "token-interno";
    private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

    private HttpServer server;

    @BeforeAll
    static void iniciar() {
        WIREMOCK.start();
    }

    @AfterAll
    static void detener() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void limpiar() {
        WIREMOCK.resetAll();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void respuestaDeSeatInventorySeConvierteEnMapaPorItinerario() throws IOException {
        UUID conocido = UUID.randomUUID();
        UUID desconocido = UUID.randomUUID();
        SeatInventoryClient client = clientContra(200,
                "[{\"itineraryId\":\"" + conocido + "\",\"disponibles\":7,\"total\":40}]");

        assertThat(client.availability(List.of(conocido, desconocido))).contains(Map.of(conocido, 7));
    }

    @Test
    void respuestaVaciaEsUnMapaVacio() throws IOException {
        SeatInventoryClient client = clientContra(200, "");

        assertThat(client.availability(List.of(UUID.randomUUID()))).contains(Map.of());
    }

    @Test
    void errorDeSeatInventoryDevuelveOptionalVacio() throws IOException {
        SeatInventoryClient client = clientContra(500, "{}");

        assertThat(client.availability(List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void ocupacionAgrupadaDevuelveLosIndicadores() throws IOException {
        UUID id = UUID.randomUUID();
        String body = "[{\"itineraryId\":\"" + id + "\",\"vendidos\":10,\"disponibles\":20,"
                + "\"bloqueados\":2,\"porcentaje\":31.25,\"tasaAbandono\":4.5}]";
        var result = clientOccupancy(200, body).occupancy(List.of(id));
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().get(id).vendidos()).isEqualTo(10);
    }

    @Test
    void ocupacionAceptaElObjetoEnvolventeYRespuestasSinContenido() throws IOException {
        UUID id = UUID.randomUUID();
        var wrapped = clientOccupancy(200, "{\"occupaciones\":[{\"itineraryId\":\"" + id
                + "\",\"vendidos\":1,\"disponibles\":2,\"bloqueados\":3,\"porcentaje\":25,\"tasaAbandono\":0}]}")
                .occupancy(List.of(id));
        assertThat(wrapped.orElseThrow()).containsKey(id);
        assertThat(clientOccupancy(204, "").occupancy(List.of(id))).contains(Map.of());
        assertThat(clientOccupancy(500, "{}").occupancy(List.of(id))).isEmpty();
        assertThat(wiremockClient().occupancy(List.of())).contains(Map.of());
    }

    @Test
    void seatInventoryInalcanzableDevuelveOptionalVacio() {
        SeatInventoryClient client = new SeatInventoryClient(new ServiceUrls("http://localhost:1"), TOKEN);

        assertThat(client.availability(List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void sinItinerariosNoConsultaYRespondeMapaVacio() {
        SeatInventoryClient client = new SeatInventoryClient(new ServiceUrls("http://localhost:1"), TOKEN);

        assertThat(client.availability(List.of())).contains(Map.of());
    }

    @Test
    void remapEnviaLosAsientosConElTokenInterno() {
        UUID viaje = UUID.randomUUID();
        WIREMOCK.stubFor(post(urlEqualTo("/internal/itineraries/" + viaje + "/remap"))
                .willReturn(aResponse().withStatus(204)));

        var r = wiremockClient().remap(viaje, List.of("1", "2"), 2);

        assertThat(r.status()).isEqualTo(SeatInventoryClient.RemapResult.Status.OK);
        WIREMOCK.verify(postRequestedFor(urlEqualTo("/internal/itineraries/" + viaje + "/remap"))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withRequestBody(equalToJson("{\"seatNumbers\":[\"1\",\"2\"],\"capacidad\":2}")));
    }

    @Test
    void remapConConflictoTraeLosAsientosQueNoExisten() {
        UUID viaje = UUID.randomUUID();
        WIREMOCK.stubFor(post(urlEqualTo("/internal/itineraries/" + viaje + "/remap"))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":\"SEATS_CONFLICT\",\"details\":{\"asientos\":[\"39\",\"40\"]}}}")));

        var r = wiremockClient().remap(viaje, List.of("1"), 1);

        assertThat(r.status()).isEqualTo(SeatInventoryClient.RemapResult.Status.SEATS_CONFLICT);
        assertThat(r.asientosEnConflicto()).containsExactly("39", "40");
    }

    @Test
    void remapConErrorOTimeoutEsNoDisponible() {
        UUID viaje = UUID.randomUUID();
        WIREMOCK.stubFor(post(urlEqualTo("/internal/itineraries/" + viaje + "/remap"))
                .willReturn(aResponse().withStatus(500)));
        assertThat(wiremockClient().remap(viaje, List.of("1"), 1).status())
                .isEqualTo(SeatInventoryClient.RemapResult.Status.UNAVAILABLE);

        UUID lento = UUID.randomUUID();
        WIREMOCK.stubFor(post(urlEqualTo("/internal/itineraries/" + lento + "/remap"))
                .willReturn(aResponse().withStatus(204).withFixedDelay(1500)));
        assertThat(wiremockClient().remap(lento, List.of("1"), 1).status())
                .isEqualTo(SeatInventoryClient.RemapResult.Status.UNAVAILABLE);

        // 404 tampoco es éxito: Catalog no cambia el bus si no sabe que Seat Inventory reasignó los asientos.
        assertThat(wiremockClient().remap(UUID.randomUUID(), List.of("1"), 1).status())
                .isEqualTo(SeatInventoryClient.RemapResult.Status.UNAVAILABLE);
    }

    @Test
    void asientosEnConflictoAceptaOtrosCamposYRespuestasMalFormadas() {
        assertThat(SeatInventoryClient.asientosEnConflicto(
                "{\"error\":{\"details\":{\"seatNumbers\":[\"7\"]}}}")).containsExactly("7");
        assertThat(SeatInventoryClient.asientosEnConflicto("{\"error\":{\"details\":{}}}")).isEmpty();
        assertThat(SeatInventoryClient.asientosEnConflicto("")).isEmpty();
        assertThat(SeatInventoryClient.asientosEnConflicto("{no es json")).isEmpty();
    }

    private static SeatInventoryClient wiremockClient() {
        return new SeatInventoryClient(new ServiceUrls(WIREMOCK.baseUrl()), TOKEN);
    }

    private SeatInventoryClient clientContra(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/seats/availability", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return new SeatInventoryClient(new ServiceUrls("http://localhost:" + server.getAddress().getPort()), TOKEN);
    }

    private SeatInventoryClient clientOccupancy(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/occupancy", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) {
                try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            }
        });
        server.start();
        return new SeatInventoryClient(new ServiceUrls("http://localhost:" + server.getAddress().getPort()), TOKEN);
    }
}
