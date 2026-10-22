package com.tripbus.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.BusType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatMapValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(BusType.class)
    void elLayoutPorDefectoEsValidoYQuedaIgualAlDeSeatLayoutFactory(BusType tipo) {
        JsonNode original = mapper.valueToTree(SeatLayoutFactory.layout(tipo));
        SeatMapValidator.Resultado r = SeatMapValidator.validar(original, tipo);
        assertThat(r.capacidad()).isEqualTo(SeatLayoutFactory.capacidad(tipo));
        assertThat(r.distribucion()).isEqualTo(original);
    }

    @Test
    void normalizaElMapaRecalculaLaCapacidadYDescartaCamposExtra() {
        ObjectNode mapa = mapper.createObjectNode();
        mapa.put("tipo", "DOS_PISOS");
        mapa.put("capacidad", 999);
        mapa.put("extra", "x");
        ObjectNode piso = mapa.putArray("pisos").addObject();
        piso.put("piso", 1).put("filas", 2).put("columnas", 3).put("color", "rojo");
        ArrayNode celdas = piso.putArray("celdas");
        celdas.addObject().put("fila", 0).put("columna", 1).put("tipo", "CONDUCTOR");
        celdas.addObject().put("fila", 1).put("columna", 1).put("tipo", "ASIENTO").put("asiento", 7);
        celdas.addObject().put("fila", 1).put("columna", 3).put("tipo", "ASIENTO").put("asiento", " 8A ");
        celdas.addObject().put("fila", 1).put("columna", 2).put("tipo", "PASILLO").putNull("asiento");

        SeatMapValidator.Resultado r = SeatMapValidator.validar(mapa, BusType.UN_PISO);

        assertThat(r.capacidad()).isEqualTo(2);
        assertThat(r.distribucion().get("tipo").asText()).isEqualTo("UN_PISO");
        assertThat(r.distribucion().get("capacidad").asInt()).isEqualTo(2);
        assertThat(r.distribucion().has("extra")).isFalse();
        JsonNode pisoOut = r.distribucion().get("pisos").get(0);
        assertThat(pisoOut.has("color")).isFalse();
        assertThat(pisoOut.get("celdas").get(1).get("asiento").asText()).isEqualTo("7");
        assertThat(pisoOut.get("celdas").get(2).get("asiento").asText()).isEqualTo("8A");
        assertThat(pisoOut.get("celdas").get(3).has("asiento")).isFalse();
    }

    @Test
    void rechazaLoQueNoEsUnObjetoOSinPisos() {
        invalido(null, BusType.UN_PISO, "objeto JSON");
        invalido(mapper.createArrayNode(), BusType.UN_PISO, "objeto JSON");
        invalido(mapper.createObjectNode(), BusType.UN_PISO, "al menos un piso");
        ObjectNode vacio = mapper.createObjectNode();
        vacio.putArray("pisos");
        invalido(vacio, BusType.UN_PISO, "al menos un piso");
        invalido(mapper.createObjectNode().put("pisos", "uno"), BusType.UN_PISO, "al menos un piso");
    }

    @Test
    void rechazaMasDeDosPisosYPisosQueNoCorrespondenAlTipo() {
        ObjectNode tres = mapper.createObjectNode();
        ArrayNode pisos = tres.putArray("pisos");
        for (int i = 1; i <= 3; i++) {
            pisos.add(pisoConUnAsiento(i, String.valueOf(i)));
        }
        invalido(tres, BusType.DOS_PISOS, "máximo 2 pisos");

        JsonNode dosPisos = mapper.valueToTree(SeatLayoutFactory.layout(BusType.DOS_PISOS));
        invalido(dosPisos, BusType.UN_PISO, "debe tener 1 piso");
        JsonNode unPiso = mapper.valueToTree(SeatLayoutFactory.layout(BusType.UN_PISO));
        invalido(unPiso, BusType.DOS_PISOS, "debe tener 2 pisos");
    }

    @Test
    void rechazaPisosRepetidosOMalFormados() {
        ObjectNode repetido = mapper.createObjectNode();
        repetido.putArray("pisos").add(pisoConUnAsiento(1, "1")).add(pisoConUnAsiento(1, "2"));
        invalido(repetido, BusType.DOS_PISOS, "piso 1 está repetido");

        ObjectNode noObjeto = mapper.createObjectNode();
        noObjeto.putArray("pisos").add(5);
        invalido(noObjeto, BusType.UN_PISO, "Cada piso debe ser un objeto");

        invalido(conPiso(p -> p.put("piso", 3)), BusType.UN_PISO, "'piso'");
        invalido(conPiso(p -> p.put("filas", 0)), BusType.UN_PISO, "'filas'");
        invalido(conPiso(p -> p.put("columnas", "5")), BusType.UN_PISO, "'columnas'");
        invalido(conPiso(p -> p.remove("celdas")), BusType.UN_PISO, "lista 'celdas'");
    }

    @Test
    void rechazaCeldasInvalidas() {
        invalido(conCelda(c -> c.put("fila", 9)), BusType.UN_PISO, "'fila'");
        invalido(conCelda(c -> c.put("columna", 0)), BusType.UN_PISO, "'columna'");
        invalido(conCelda(c -> c.put("tipo", "CAMA")), BusType.UN_PISO, "Tipo de celda no válido");
        invalido(conCelda(c -> c.remove("tipo")), BusType.UN_PISO, "Tipo de celda no válido");
        invalido(conCelda(c -> c.remove("asiento")), BusType.UN_PISO, "debe tener número de asiento");
        invalido(conCelda(c -> c.put("asiento", "  ")), BusType.UN_PISO, "debe tener número de asiento");
        invalido(conCelda(c -> c.put("asiento", true)), BusType.UN_PISO, "debe tener número de asiento");
        invalido(conCelda(c -> c.put("tipo", "BANO")), BusType.UN_PISO, "Solo las celdas ASIENTO");

        ObjectNode noObjeto = pisoConUnAsiento(1, "1");
        ((ArrayNode) noObjeto.get("celdas")).add("ASIENTO");
        invalido(envolver(noObjeto), BusType.UN_PISO, "debe ser un objeto");
    }

    @Test
    void rechazaNumerosDeAsientoYPosicionesRepetidas() {
        ObjectNode piso = pisoConUnAsiento(1, "1");
        ((ArrayNode) piso.get("celdas")).addObject().put("fila", 1).put("columna", 2).put("tipo", "ASIENTO")
                .put("asiento", "1");
        invalido(envolver(piso), BusType.UN_PISO, "asiento 1 está repetido");

        ObjectNode piso2 = pisoConUnAsiento(1, "1");
        ((ArrayNode) piso2.get("celdas")).addObject().put("fila", 1).put("columna", 1).put("tipo", "PASILLO");
        invalido(envolver(piso2), BusType.UN_PISO, "está repetida");

        // El mismo número en pisos distintos también se repite.
        ObjectNode dos = mapper.createObjectNode();
        dos.putArray("pisos").add(pisoConUnAsiento(1, "5")).add(pisoConUnAsiento(2, "5"));
        invalido(dos, BusType.DOS_PISOS, "asiento 5 está repetido");
    }

    @Test
    void exigeAlMenosUnAsiento() {
        ObjectNode piso = mapper.createObjectNode().put("piso", 1).put("filas", 1).put("columnas", 1);
        piso.putArray("celdas").addObject().put("fila", 0).put("columna", 1).put("tipo", "PASILLO");
        invalido(envolver(piso), BusType.UN_PISO, "al menos un asiento");
    }

    private ObjectNode pisoConUnAsiento(int numero, String asiento) {
        ObjectNode piso = mapper.createObjectNode().put("piso", numero).put("filas", 2).put("columnas", 5);
        piso.putArray("celdas").addObject().put("fila", 1).put("columna", 1).put("tipo", "ASIENTO")
                .put("asiento", asiento);
        return piso;
    }

    private ObjectNode envolver(ObjectNode piso) {
        ObjectNode mapa = mapper.createObjectNode();
        mapa.putArray("pisos").add(piso);
        return mapa;
    }

    private ObjectNode conPiso(Consumer<ObjectNode> cambio) {
        ObjectNode piso = pisoConUnAsiento(1, "1");
        cambio.accept(piso);
        return envolver(piso);
    }

    private ObjectNode conCelda(Consumer<ObjectNode> cambio) {
        ObjectNode piso = pisoConUnAsiento(1, "1");
        cambio.accept((ObjectNode) piso.get("celdas").get(0));
        return envolver(piso);
    }

    private static void invalido(JsonNode mapa, BusType tipo, String mensaje) {
        assertThatThrownBy(() -> SeatMapValidator.validar(mapa, tipo))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(mensaje)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("INVALID_SEATMAP");
    }
}
