package com.tripbus.catalog.service;

import java.time.ZoneId;

public final class CatalogTime {

    /** Las fechas de búsqueda y los horarios del seed se interpretan en hora de Colombia. */
    public static final ZoneId ZONE = ZoneId.of("America/Bogota");

    private CatalogTime() {
    }
}
