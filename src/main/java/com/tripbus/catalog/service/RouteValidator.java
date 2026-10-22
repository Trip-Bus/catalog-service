package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.web.dto.StopRequest;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * US-114: reglas de una ruta y sus paradas. Junta todos los problemas y responde un solo
 * 400 INVALID_ROUTE con details {campo: motivo} (p. ej. "paradas[2].lat").
 */
public final class RouteValidator {

    public static final String ERROR = "INVALID_ROUTE";
    static final double LAT_MIN = -4.3;
    static final double LAT_MAX = 13.5;
    static final double LNG_MIN = -79.1;
    static final double LNG_MAX = -66.8;
    static final int MAX_CIUDAD = 80;
    static final int MAX_PARADA = 120;

    private RouteValidator() {
    }

    /** Valida y devuelve la duración de la ruta (minutos de la última parada). */
    public static int validar(String origen, String destino, List<StopRequest> paradas) {
        Map<String, Object> errores = new LinkedHashMap<>();
        texto(errores, "origen", origen, MAX_CIUDAD, "El origen");
        texto(errores, "destino", destino, MAX_CIUDAD, "El destino");
        String origenNorm = TextNormalizer.normalize(origen);
        String destinoNorm = TextNormalizer.normalize(destino);
        if (!origenNorm.isEmpty() && origenNorm.equals(destinoNorm)) {
            errores.put("destino", "El destino debe ser distinto del origen");
        }

        if (paradas == null || paradas.size() < 2) {
            errores.put("paradas", "La ruta debe tener al menos 2 paradas (origen y destino)");
            throw invalida(errores);
        }
        Integer anterior = null;
        for (int i = 0; i < paradas.size(); i++) {
            String campo = "paradas[" + i + "]";
            StopRequest p = paradas.get(i);
            if (p == null) {
                errores.put(campo, "La parada es obligatoria");
                anterior = null;
                continue;
            }
            if (p.orden() == null || p.orden() != i + 1) {
                errores.put(campo + ".orden", "Debe ser " + (i + 1) + " (orden consecutivo desde 1)");
            }
            texto(errores, campo + ".nombre", p.nombre(), MAX_PARADA, "El nombre de la parada");
            if (p.lat() == null || p.lat() < LAT_MIN || p.lat() > LAT_MAX) {
                errores.put(campo + ".lat", "La latitud debe estar en Colombia (" + LAT_MIN + " a " + LAT_MAX + ")");
            }
            if (p.lng() == null || p.lng() < LNG_MIN || p.lng() > LNG_MAX) {
                errores.put(campo + ".lng", "La longitud debe estar en Colombia (" + LNG_MIN + " a " + LNG_MAX + ")");
            }
            anterior = minutos(errores, campo, i, p.minutosDesdeSalida(), anterior);
        }
        extremo(errores, paradas.get(0), "paradas[0].nombre", origenNorm, "La primera parada debe ser el origen");
        extremo(errores, paradas.get(paradas.size() - 1), "paradas[" + (paradas.size() - 1) + "].nombre",
                destinoNorm, "La última parada debe ser el destino");
        if (!errores.isEmpty()) {
            throw invalida(errores);
        }
        return paradas.get(paradas.size() - 1).minutosDesdeSalida();
    }

    private static Integer minutos(Map<String, Object> errores, String campo, int i, Integer minutos, Integer anterior) {
        if (minutos == null) {
            errores.put(campo + ".minutosDesdeSalida", "Los minutos desde la salida son obligatorios");
            return null;
        }
        if (i == 0 && minutos != 0) {
            errores.put(campo + ".minutosDesdeSalida", "La primera parada debe tener 0 minutos desde la salida");
        } else if (i > 0 && anterior != null && minutos <= anterior) {
            errores.put(campo + ".minutosDesdeSalida", "Debe ser mayor que la parada anterior (" + anterior + ")");
        }
        return minutos;
    }

    private static void extremo(Map<String, Object> errores, StopRequest parada, String campo, String esperado,
                                String mensaje) {
        if (parada != null && !esperado.isEmpty() && parada.nombre() != null && !parada.nombre().isBlank()
                && !TextNormalizer.normalize(parada.nombre()).equals(esperado)) {
            errores.putIfAbsent(campo, mensaje);
        }
    }

    private static void texto(Map<String, Object> errores, String campo, String valor, int max, String nombre) {
        if (valor == null || valor.isBlank()) {
            errores.put(campo, nombre + " es obligatorio");
        } else if (valor.trim().length() > max) {
            errores.put(campo, nombre + " no puede superar " + max + " caracteres");
        }
    }

    private static ApiException invalida(Map<String, Object> errores) {
        return new ApiException(HttpStatus.BAD_REQUEST, ERROR, "La ruta no es válida", errores);
    }
}
