package com.tripbus.catalog.seed;

import com.tripbus.catalog.domain.BusType;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * Datos base de demostración (HT-01). Empresas ficticias y rutas intermunicipales reales
 * de Colombia con paradas y coordenadas aproximadas.
 */
final class SeedData {

    private SeedData() {
    }

    record CompanySeed(String key, String nombre, String nit) {
    }

    record StopSeed(String nombre, double lat, double lng, int minutosDesdeSalida) {
    }

    record RouteSeed(String key, String companyKey, BusType tipoBus, BigDecimal precioBase, List<StopSeed> paradas) {

        String origen() {
            return paradas.get(0).nombre();
        }

        String destino() {
            return paradas.get(paradas.size() - 1).nombre();
        }

        int duracionMinutos() {
            return paradas.get(paradas.size() - 1).minutosDesdeSalida();
        }
    }

    /** Horarios diarios de salida (hora de Colombia) para cada ruta. */
    static final List<LocalTime> SALIDAS = List.of(
            LocalTime.of(5, 30), LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(17, 30), LocalTime.of(22, 0));

    /** Días generados: desde ayer (para tener viajes en curso) hasta DIAS_ADELANTE. */
    static final int DIAS_ATRAS = 1;
    static final int DIAS_ADELANTE = 14;

    static final List<CompanySeed> EMPRESAS = List.of(
            new CompanySeed("andino", "Expreso Andino", "900100001-1"),
            new CompanySeed("cordillera", "Flota Cordillera", "900100002-2"),
            new CompanySeed("caribe", "Transportes Caribe Sol", "900100003-3"));

    private static StopSeed s(String nombre, double lat, double lng, int min) {
        return new StopSeed(nombre, lat, lng, min);
    }

    static final List<RouteSeed> RUTAS = List.of(
            new RouteSeed("bog-med", "andino", BusType.DOS_PISOS, new BigDecimal("95000"), List.of(
                    s("Bogotá", 4.6584, -74.1155, 0),
                    s("Honda", 5.2069, -74.7356, 210),
                    s("Puerto Triunfo", 5.8726, -74.6417, 330),
                    s("Medellín", 6.2789, -75.5703, 540))),
            new RouteSeed("med-bog", "andino", BusType.DOS_PISOS, new BigDecimal("95000"), List.of(
                    s("Medellín", 6.2789, -75.5703, 0),
                    s("Puerto Triunfo", 5.8726, -74.6417, 210),
                    s("Honda", 5.2069, -74.7356, 330),
                    s("Bogotá", 4.6584, -74.1155, 540))),
            new RouteSeed("bog-cal", "cordillera", BusType.UN_PISO, new BigDecimal("105000"), List.of(
                    s("Bogotá", 4.6584, -74.1155, 0),
                    s("Girardot", 4.3032, -74.8037, 150),
                    s("Ibagué", 4.4389, -75.2322, 240),
                    s("Armenia", 4.5339, -75.6811, 390),
                    s("Cali", 3.4706, -76.5267, 600))),
            new RouteSeed("cal-bog", "cordillera", BusType.UN_PISO, new BigDecimal("105000"), List.of(
                    s("Cali", 3.4706, -76.5267, 0),
                    s("Armenia", 4.5339, -75.6811, 210),
                    s("Ibagué", 4.4389, -75.2322, 360),
                    s("Girardot", 4.3032, -74.8037, 450),
                    s("Bogotá", 4.6584, -74.1155, 600))),
            new RouteSeed("bog-buc", "cordillera", BusType.UN_PISO, new BigDecimal("90000"), List.of(
                    s("Bogotá", 4.6584, -74.1155, 0),
                    s("Tunja", 5.5353, -73.3678, 150),
                    s("Barbosa", 5.9318, -73.6152, 240),
                    s("San Gil", 6.5545, -73.1335, 390),
                    s("Bucaramanga", 7.1193, -73.1227, 540))),
            new RouteSeed("med-ctg", "caribe", BusType.DOS_PISOS, new BigDecimal("140000"), List.of(
                    s("Medellín", 6.2789, -75.5703, 0),
                    s("Caucasia", 7.9865, -75.1934, 300),
                    s("Sincelejo", 9.3047, -75.3978, 570),
                    s("Cartagena", 10.3910, -75.4794, 780))),
            new RouteSeed("bog-vil", "andino", BusType.UN_PISO, new BigDecimal("38000"), List.of(
                    s("Bogotá", 4.5940, -74.0750, 0),
                    s("Chipaque", 4.4425, -74.0447, 45),
                    s("Villavicencio", 4.1420, -73.6266, 180))),
            new RouteSeed("bog-tun", "caribe", BusType.UN_PISO, new BigDecimal("28000"), List.of(
                    s("Bogotá", 4.7562, -74.0447, 0),
                    s("Chocontá", 5.1447, -73.6853, 75),
                    s("Tunja", 5.5353, -73.3678, 150))));
}
