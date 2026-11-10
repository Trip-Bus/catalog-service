package com.tripbus.catalog.repository;

import java.util.UUID;

/**
 * US-138: tramo de una ruta entre dos de sus paradas (la de origen antes que la de destino), con los minutos
 * de cada una desde la salida y la duración total de la ruta.
 */
public record TramoParada(UUID routeId, int fromOrden, int toOrden, int minutosDesde, int minutosHasta,
                          int duracionRuta) {

    /** El tramo es el recorrido completo: de la primera parada a la última. */
    public boolean esCompleto() {
        return fromOrden == 1 && minutosHasta == duracionRuta;
    }
}
