package com.tripbus.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.BusType;

import java.util.HashSet;
import java.util.Set;

/**
 * US-111: valida un mapa de asientos enviado por la empresa y lo devuelve normalizado al MISMO formato
 * que genera {@link SeatLayoutFactory} ({tipo, capacidad, pisos[{piso, filas, columnas, celdas[{fila,
 * columna, tipo, asiento?}]}]}), sin campos extra, para que /itineraries/{id}/seatmap y el frontend no cambien.
 * La capacidad es el número de celdas ASIENTO. Cualquier error es 400 INVALID_SEATMAP.
 */
public final class SeatMapValidator {

    public static final String ERROR = "INVALID_SEATMAP";
    public static final int MAX_PISOS = 2;
    static final int MAX_FILAS = 30;
    static final int MAX_COLUMNAS = 10;
    static final Set<String> TIPOS_CELDA =
            Set.of("ASIENTO", "PASILLO", "BANO", "CONDUCTOR", "ESCALERA", "PUERTA", "VACIO");

    public record Resultado(ObjectNode distribucion, int capacidad) {
    }

    private SeatMapValidator() {
    }

    public static Resultado validar(JsonNode distribucion, BusType tipo) {
        if (distribucion == null || !distribucion.isObject()) {
            throw invalido("El mapa de asientos debe ser un objeto JSON con la lista 'pisos'");
        }
        JsonNode pisos = distribucion.get("pisos");
        if (pisos == null || !pisos.isArray() || pisos.isEmpty()) {
            throw invalido("El mapa de asientos debe tener al menos un piso");
        }
        if (pisos.size() > MAX_PISOS) {
            throw invalido("El mapa de asientos admite máximo " + MAX_PISOS + " pisos");
        }
        int esperados = tipo == BusType.DOS_PISOS ? 2 : 1;
        if (pisos.size() != esperados) {
            throw invalido("Un bus " + tipo + " debe tener " + esperados + (esperados == 1 ? " piso" : " pisos"));
        }

        JsonNodeFactory f = JsonNodeFactory.instance;
        ArrayNode pisosOut = f.arrayNode();
        Set<Integer> numerosPiso = new HashSet<>();
        Set<String> asientos = new HashSet<>();
        for (JsonNode piso : pisos) {
            if (!piso.isObject()) {
                throw invalido("Cada piso debe ser un objeto");
            }
            int numero = entero(piso, "piso", 1, MAX_PISOS, "el piso");
            if (!numerosPiso.add(numero)) {
                throw invalido("El piso " + numero + " está repetido");
            }
            String donde = "el piso " + numero;
            int filas = entero(piso, "filas", 1, MAX_FILAS, donde);
            int columnas = entero(piso, "columnas", 1, MAX_COLUMNAS, donde);
            JsonNode celdas = piso.get("celdas");
            if (celdas == null || !celdas.isArray()) {
                throw invalido("El piso " + numero + " debe tener la lista 'celdas'");
            }
            ArrayNode celdasOut = f.arrayNode();
            Set<String> posiciones = new HashSet<>();
            for (JsonNode celda : celdas) {
                celdasOut.add(celda(celda, filas, columnas, donde, posiciones, asientos));
            }
            ObjectNode pisoOut = f.objectNode();
            pisoOut.put("piso", numero);
            pisoOut.put("filas", filas);
            pisoOut.put("columnas", columnas);
            pisoOut.set("celdas", celdasOut);
            pisosOut.add(pisoOut);
        }
        if (asientos.isEmpty()) {
            throw invalido("El mapa de asientos debe tener al menos un asiento");
        }

        ObjectNode out = f.objectNode();
        out.put("tipo", tipo.name());
        out.put("capacidad", asientos.size());
        out.set("pisos", pisosOut);
        return new Resultado(out, asientos.size());
    }

    private static ObjectNode celda(JsonNode celda, int filas, int columnas, String donde,
                                    Set<String> posiciones, Set<String> asientos) {
        if (!celda.isObject()) {
            throw invalido("Cada celda de " + donde + " debe ser un objeto");
        }
        int fila = entero(celda, "fila", 0, filas - 1, donde);
        int columna = entero(celda, "columna", 1, columnas, donde);
        String posicion = donde + ", fila " + fila + ", columna " + columna;
        if (!posiciones.add(fila + ":" + columna)) {
            throw invalido("La celda de " + posicion + " está repetida");
        }
        JsonNode tipoNode = celda.get("tipo");
        String tipo = tipoNode != null && tipoNode.isTextual() ? tipoNode.asText() : "";
        if (!TIPOS_CELDA.contains(tipo)) {
            throw invalido("Tipo de celda no válido en " + posicion + ": debe ser uno de " + TIPOS_CELDA);
        }
        JsonNode asiento = celda.get("asiento");
        boolean tieneAsiento = asiento != null && !asiento.isNull();

        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("fila", fila);
        out.put("columna", columna);
        out.put("tipo", tipo);
        if ("ASIENTO".equals(tipo)) {
            String numero = tieneAsiento && (asiento.isTextual() || asiento.isIntegralNumber())
                    ? asiento.asText().trim() : "";
            if (numero.isEmpty()) {
                throw invalido("La celda ASIENTO de " + posicion + " debe tener número de asiento");
            }
            if (!asientos.add(numero)) {
                throw invalido("El número de asiento " + numero + " está repetido");
            }
            out.put("asiento", numero);
        } else if (tieneAsiento) {
            throw invalido("Solo las celdas ASIENTO llevan número (" + posicion + ")");
        }
        return out;
    }

    private static int entero(JsonNode node, String campo, int min, int max, String donde) {
        JsonNode valor = node.get(campo);
        if (valor == null || !valor.isIntegralNumber() || !valor.canConvertToInt()
                || valor.asInt() < min || valor.asInt() > max) {
            throw invalido("'" + campo + "' en " + donde + " debe ser un entero entre " + min + " y " + max);
        }
        return valor.asInt();
    }

    private static ApiException invalido(String mensaje) {
        return ApiException.badRequest(ERROR, mensaje);
    }
}
