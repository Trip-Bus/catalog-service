package com.tripbus.catalog.seed;

import com.tripbus.catalog.domain.BusType;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Datos base de demostración (HT-01). Empresas ficticias y rutas intermunicipales reales
 * de Colombia con paradas y coordenadas aproximadas.
 *
 * <p>Hay viajes entre <b>todas</b> las ciudades del catálogo, en los dos sentidos, para que la
 * demo nunca muestre "ningún bus sale ese día". Las 8 rutas originales están escritas a mano;
 * las demás se arman con el camino más corto sobre la red de carreteras de abajo ({@link #TRAMOS}),
 * con los pueblos del camino como paradas.
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
    static final int DIAS_ADELANTE = 21; // el selector de fecha del front llega a 20 días

    /** Referencia para alternar buses en días pares/impares (la misma que usa la migración V5). */
    private static final LocalDate DIA_CERO = LocalDate.of(2000, 1, 1);

    /**
     * US-117: el bus de un horario sale todos los días a la misma hora. Si la ruta más el alistamiento no
     * cabe en 24 h, los días impares necesitan otro bus.
     */
    static boolean necesitaBusAlterno(RouteSeed ruta, Duration alistamiento) {
        return Duration.ofMinutes(ruta.duracionMinutos()).plus(alistamiento).compareTo(Duration.ofDays(1)) > 0;
    }

    static boolean esDiaImpar(LocalDate fecha) {
        return Math.floorMod(ChronoUnit.DAYS.between(DIA_CERO, fecha), 2) == 1;
    }

    static final List<CompanySeed> EMPRESAS = List.of(
            new CompanySeed("andino", "Expreso Andino", "900100001-1"),
            new CompanySeed("cordillera", "Flota Cordillera", "900100002-2"),
            new CompanySeed("caribe", "Transportes Caribe Sol", "900100003-3"));

    private static StopSeed s(String nombre, double lat, double lng, int min) {
        return new StopSeed(nombre, lat, lng, min);
    }

    /** Rutas originales (no cambiar su orden: la placa de cada bus depende de la posición). */
    private static final List<RouteSeed> BASE = List.of(
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

    /** Ciudades del catálogo: hay rutas entre cada par, en los dos sentidos. */
    static final List<String> CIUDADES = List.of(
            "Bogotá", "Medellín", "Cali", "Cartagena", "Bucaramanga", "Tunja", "Villavicencio");

    /** Lugares de la red (ciudades y pueblos de paso) con coordenadas aproximadas. */
    private static final Map<String, double[]> LUGARES = lugares();

    private static Map<String, double[]> lugares() {
        Map<String, double[]> m = new LinkedHashMap<>();
        m.put("Bogotá", new double[] {4.6584, -74.1155});
        m.put("Chipaque", new double[] {4.4425, -74.0447});
        m.put("Villavicencio", new double[] {4.1420, -73.6266});
        m.put("Chocontá", new double[] {5.1447, -73.6853});
        m.put("Tunja", new double[] {5.5353, -73.3678});
        m.put("Barbosa", new double[] {5.9318, -73.6152});
        m.put("San Gil", new double[] {6.5545, -73.1335});
        m.put("Bucaramanga", new double[] {7.1193, -73.1227});
        m.put("Honda", new double[] {5.2069, -74.7356});
        m.put("Puerto Triunfo", new double[] {5.8726, -74.6417});
        m.put("Medellín", new double[] {6.2789, -75.5703});
        m.put("Girardot", new double[] {4.3032, -74.8037});
        m.put("Ibagué", new double[] {4.4389, -75.2322});
        m.put("Armenia", new double[] {4.5339, -75.6811});
        m.put("Cali", new double[] {3.4706, -76.5267});
        m.put("Caucasia", new double[] {7.9865, -75.1934});
        m.put("Sincelejo", new double[] {9.3047, -75.3978});
        m.put("Cartagena", new double[] {10.3910, -75.4794});
        m.put("La Pintada", new double[] {5.7436, -75.6061});
        m.put("Pereira", new double[] {4.8133, -75.6961});
        m.put("Barrancabermeja", new double[] {7.0653, -73.8547});
        m.put("Puerto Berrío", new double[] {6.4920, -74.4033});
        m.put("Aguachica", new double[] {8.3100, -73.6166});
        m.put("Bosconia", new double[] {9.9761, -73.8903});
        return Collections.unmodifiableMap(m);
    }

    record Tramo(String a, String b, int minutos) {
    }

    /** Red de carreteras (doble sentido) con minutos aproximados de bus entre lugares vecinos. */
    static final List<Tramo> TRAMOS = List.of(
            new Tramo("Bogotá", "Chipaque", 45),
            new Tramo("Chipaque", "Villavicencio", 135),
            new Tramo("Bogotá", "Chocontá", 75),
            new Tramo("Chocontá", "Tunja", 75),
            new Tramo("Tunja", "Barbosa", 90),
            new Tramo("Barbosa", "San Gil", 150),
            new Tramo("San Gil", "Bucaramanga", 150),
            new Tramo("Bogotá", "Honda", 210),
            new Tramo("Honda", "Puerto Triunfo", 120),
            new Tramo("Puerto Triunfo", "Medellín", 210),
            new Tramo("Bogotá", "Girardot", 150),
            new Tramo("Girardot", "Ibagué", 90),
            new Tramo("Ibagué", "Armenia", 150),
            new Tramo("Armenia", "Cali", 210),
            new Tramo("Medellín", "Caucasia", 300),
            new Tramo("Caucasia", "Sincelejo", 270),
            new Tramo("Sincelejo", "Cartagena", 210),
            new Tramo("Medellín", "La Pintada", 120),
            new Tramo("La Pintada", "Pereira", 150),
            new Tramo("Pereira", "Armenia", 60),
            new Tramo("Bucaramanga", "Barrancabermeja", 150),
            new Tramo("Barrancabermeja", "Puerto Berrío", 180),
            new Tramo("Puerto Berrío", "Medellín", 240),
            new Tramo("Bucaramanga", "Aguachica", 240),
            new Tramo("Aguachica", "Bosconia", 240),
            new Tramo("Bosconia", "Cartagena", 300));

    /** Todas las rutas: las originales y, después, las generadas para los pares que faltan. */
    static final List<RouteSeed> RUTAS = rutas();

    private static List<RouteSeed> rutas() {
        List<RouteSeed> todas = new ArrayList<>(BASE);
        Set<String> cubiertos = new HashSet<>();
        Map<String, RouteSeed> basePorPar = new HashMap<>();
        for (RouteSeed r : BASE) {
            cubiertos.add(r.origen() + ">" + r.destino());
            basePorPar.put(r.origen() + ">" + r.destino(), r);
        }
        String[] empresas = {"andino", "cordillera", "caribe"};
        int n = 0;
        for (String origen : CIUDADES) {
            for (String destino : CIUDADES) {
                if (origen.equals(destino) || cubiertos.contains(origen + ">" + destino)) {
                    continue;
                }
                String llave = slug(origen) + "-" + slug(destino);
                RouteSeed ida = basePorPar.get(destino + ">" + origen);
                if (ida != null) {
                    // El regreso de una ruta original: mismas paradas al revés, misma empresa, bus y tarifa.
                    todas.add(new RouteSeed(llave, ida.companyKey(), ida.tipoBus(), ida.precioBase(), alReves(ida.paradas())));
                    continue;
                }
                List<StopSeed> paradas = camino(origen, destino);
                int minutos = paradas.get(paradas.size() - 1).minutosDesdeSalida();
                BusType tipo = minutos >= 600 ? BusType.DOS_PISOS : BusType.UN_PISO;
                todas.add(new RouteSeed(llave, empresas[n % empresas.length], tipo, precioPorDuracion(minutos), paradas));
                n++;
            }
        }
        return Collections.unmodifiableList(todas);
    }

    private static List<StopSeed> alReves(List<StopSeed> ida) {
        int total = ida.get(ida.size() - 1).minutosDesdeSalida();
        List<StopSeed> regreso = new ArrayList<>();
        for (int i = ida.size() - 1; i >= 0; i--) {
            StopSeed p = ida.get(i);
            regreso.add(new StopSeed(p.nombre(), p.lat(), p.lng(), total - p.minutosDesdeSalida()));
        }
        return regreso;
    }

    /** Tarifa de referencia: unos $180 por minuto de viaje, redondeada a mil y mínimo $25.000. */
    static BigDecimal precioPorDuracion(int minutos) {
        long pesos = Math.max(25_000L, Math.round(minutos * 180 / 1000.0) * 1000L);
        return BigDecimal.valueOf(pesos);
    }

    /** Camino más corto (Dijkstra) entre dos ciudades; los lugares del camino son las paradas. */
    static List<StopSeed> camino(String origen, String destino) {
        Map<String, List<Tramo>> vecinos = new HashMap<>();
        for (Tramo t : TRAMOS) {
            vecinos.computeIfAbsent(t.a(), k -> new ArrayList<>()).add(t);
            vecinos.computeIfAbsent(t.b(), k -> new ArrayList<>()).add(new Tramo(t.b(), t.a(), t.minutos()));
        }
        Map<String, Integer> dist = new HashMap<>();
        Map<String, String> previo = new HashMap<>();
        // Desempate por nombre: el resultado es siempre el mismo (los IDs del seed son deterministas).
        PriorityQueue<Map.Entry<String, Integer>> cola = new PriorityQueue<>(
                Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).thenComparing(Map.Entry::getKey));
        dist.put(origen, 0);
        cola.add(Map.entry(origen, 0));
        while (!cola.isEmpty()) {
            Map.Entry<String, Integer> actual = cola.poll();
            String lugar = actual.getKey();
            if (actual.getValue() > dist.get(lugar)) {
                continue;
            }
            if (lugar.equals(destino)) {
                break;
            }
            for (Tramo t : vecinos.getOrDefault(lugar, List.of())) {
                int nueva = actual.getValue() + t.minutos();
                Integer conocida = dist.get(t.b());
                if (conocida == null || nueva < conocida) {
                    dist.put(t.b(), nueva);
                    previo.put(t.b(), lugar);
                    cola.add(Map.entry(t.b(), nueva));
                }
            }
        }
        if (!dist.containsKey(destino)) {
            throw new IllegalStateException("No hay carretera entre " + origen + " y " + destino);
        }
        List<String> lugares = new ArrayList<>();
        for (String l = destino; l != null; l = previo.get(l)) {
            lugares.add(0, l);
        }
        List<StopSeed> paradas = new ArrayList<>();
        for (String l : lugares) {
            double[] c = LUGARES.get(l);
            paradas.add(new StopSeed(l, c[0], c[1], dist.get(l)));
        }
        return paradas;
    }

    private static String slug(String ciudad) {
        String sinTildes = Normalizer.normalize(ciudad, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).substring(0, 3);
    }
}
