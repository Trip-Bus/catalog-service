package com.tripbus.catalog.service;

/**
 * US-138: qué viajes trae la búsqueda.
 * <ul>
 *   <li>AUTO (sin el parámetro tramos): los directos; si no hay ninguna ruta directa entre las dos ciudades, los
 *       tramos intermedios. Así una búsqueda que ya tenía rutas directas responde igual que antes.</li>
 *   <li>TODOS (tramos=true): los directos y los tramos intermedios.</li>
 *   <li>DIRECTOS (tramos=false): solo los directos.</li>
 * </ul>
 */
public enum SearchMode {
    AUTO, TODOS, DIRECTOS;

    public static SearchMode de(Boolean tramos) {
        if (tramos == null) {
            return AUTO;
        }
        return tramos ? TODOS : DIRECTOS;
    }
}
