package com.tripbus.catalog.client;

import com.sun.net.httpserver.HttpServer;
import com.tripbus.catalog.config.ServiceUrls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** El cliente distingue "Seat Inventory respondió" (Optional con mapa) de "falló" (Optional vacío). */
class SeatInventoryClientTest {

    private HttpServer server;

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
    void errorDeSeatInventoryDevuelveOptionalVacio() throws IOException {
        SeatInventoryClient client = clientContra(500, "{}");

        assertThat(client.availability(List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void seatInventoryInalcanzableDevuelveOptionalVacio() {
        SeatInventoryClient client = new SeatInventoryClient(new ServiceUrls("http://localhost:1"));

        assertThat(client.availability(List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void sinItinerariosNoConsultaYRespondeMapaVacio() {
        SeatInventoryClient client = new SeatInventoryClient(new ServiceUrls("http://localhost:1"));

        assertThat(client.availability(List.of())).contains(Map.of());
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
        return new SeatInventoryClient(new ServiceUrls("http://localhost:" + server.getAddress().getPort()));
    }
}
