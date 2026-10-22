package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.web.dto.StopRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class RouteValidatorTest {

    private static final StopRequest BOGOTA = new StopRequest(1, "Bogotá", 4.6584, -74.1155, 0);
    private static final StopRequest TUNJA = new StopRequest(2, "Chocontá", 5.1447, -73.6853, 75);
    private static final StopRequest FIN = new StopRequest(3, "Tunja", 5.5353, -73.3678, 150);

    @Test
    void rutaValidaDevuelveLaDuracionDeLaUltimaParadaYComparaSinTildes() {
        assertThat(RouteValidator.validar("Bogota", "TUNJA", List.of(BOGOTA, TUNJA, FIN))).isEqualTo(150);
    }

    @Test
    void origenYDestinoObligatoriosYDistintos() {
        Map<String, Object> e = errores(null, " ", List.of(BOGOTA, FIN));
        assertThat(e).containsKeys("origen", "destino");

        e = errores("Bogotá", "bogota", List.of(BOGOTA, new StopRequest(2, "Bogotá", 4.6, -74.1, 30)));
        assertThat(e.get("destino")).asString().contains("distinto del origen");

        e = errores("B".repeat(81), "Tunja", List.of(BOGOTA, FIN));
        assertThat(e.get("origen")).asString().contains("80");
    }

    @Test
    void minimoDosParadas() {
        assertThat(errores("Bogotá", "Tunja", null)).containsKey("paradas");
        assertThat(errores("Bogotá", "Tunja", List.of(BOGOTA))).containsKey("paradas");
    }

    @Test
    void primeraEsElOrigenYUltimaElDestino() {
        Map<String, Object> e = errores("Tunja", "Bogotá", List.of(BOGOTA, TUNJA, FIN));
        assertThat(e.get("paradas[0].nombre")).asString().contains("origen");
        assertThat(e.get("paradas[2].nombre")).asString().contains("destino");
    }

    @Test
    void ordenConsecutivoDesdeUno() {
        Map<String, Object> e = errores("Bogotá", "Tunja", List.of(
                new StopRequest(0, "Bogotá", 4.6, -74.1, 0), new StopRequest(3, "Tunja", 5.5, -73.3, 150)));
        assertThat(e).containsKeys("paradas[0].orden", "paradas[1].orden");
        assertThat(errores("Bogotá", "Tunja", List.of(
                new StopRequest(null, "Bogotá", 4.6, -74.1, 0), new StopRequest(2, "Tunja", 5.5353, -73.3678, 150)))).containsKey("paradas[0].orden");
    }

    @Test
    void minutosEmpiezanEnCeroYCrecenEstrictamente() {
        Map<String, Object> e = errores("Bogotá", "Tunja", List.of(
                new StopRequest(1, "Bogotá", 4.6, -74.1, 5),
                new StopRequest(2, "Chocontá", 5.1, -73.6, 75),
                new StopRequest(3, "Tunja", 5.5, -73.3, 75)));
        assertThat(e).containsKeys("paradas[0].minutosDesdeSalida", "paradas[2].minutosDesdeSalida");
        assertThat(errores("Bogotá", "Tunja", List.of(
                new StopRequest(1, "Bogotá", 4.6, -74.1, null), new StopRequest(2, "Tunja", 5.5353, -73.3678, 150))))
                .containsKey("paradas[0].minutosDesdeSalida");
    }

    @Test
    void coordenadasDentroDeColombia() {
        Map<String, Object> e = errores("Bogotá", "Tunja", List.of(
                new StopRequest(1, "Bogotá", 40.4, -3.7, 0),
                new StopRequest(2, "Tunja", null, null, 100)));
        assertThat(e).containsKeys("paradas[0].lat", "paradas[0].lng", "paradas[1].lat", "paradas[1].lng");
        // Los extremos del rango sí valen.
        assertThat(RouteValidator.validar("Leticia", "Punta Gallinas", List.of(
                new StopRequest(1, "Leticia", -4.3, -69.9, 0),
                new StopRequest(2, "Punta Gallinas", 13.5, -71.7, 900)))).isEqualTo(900);
        assertThat(errores("A", "B", List.of(
                new StopRequest(1, "A", 4.0, -79.2, 0), new StopRequest(2, "B", 4.0, -66.7, 10))))
                .containsKeys("paradas[0].lng", "paradas[1].lng");
    }

    @Test
    void paradaNulaONombreVacio() {
        List<StopRequest> paradas = new ArrayList<>(Arrays.asList(BOGOTA, null, FIN));
        Map<String, Object> e = errores("Bogotá", "Tunja", paradas);
        assertThat(e).containsKey("paradas[1]");
        assertThat(errores("Bogotá", "Tunja", List.of(BOGOTA, new StopRequest(2, " ", 5.5, -73.3, 10))))
                .containsKey("paradas[1].nombre");
        assertThat(errores("Bogotá", "Tunja", List.of(BOGOTA, new StopRequest(2, "T".repeat(121), 5.5, -73.3, 10))))
                .containsKey("paradas[1].nombre");
    }

    private static Map<String, Object> errores(String origen, String destino, List<StopRequest> paradas) {
        ApiException e = catchThrowableOfType(ApiException.class, () -> RouteValidator.validar(origen, destino, paradas));
        assertThat(e).as("debía ser INVALID_ROUTE").isNotNull();
        assertThat(e.getCode()).isEqualTo("INVALID_ROUTE");
        return e.getDetails();
    }
}
