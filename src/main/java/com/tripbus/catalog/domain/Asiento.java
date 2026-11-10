package com.tripbus.catalog.domain;

/** US-141: un asiento del mapa con su categoría (ESTANDAR, PREFERENCIAL o PREMIUM). */
public record Asiento(String numero, String categoria) {
}
