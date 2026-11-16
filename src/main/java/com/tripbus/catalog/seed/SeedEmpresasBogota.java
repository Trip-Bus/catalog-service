package com.tripbus.catalog.seed;

import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.service.PricingRules;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Empresas de demostración que operan en las terminales de Bogotá (Terminal del Norte y Terminal Salitre), además de
 * las 3 ficticias de {@link SeedData} (de las que dependen los usuarios demo). Los nombres son reales; el NIT, las
 * rutas, los horarios y los precios son de demostración.
 *
 * <p>Cada empresa tiene uno o dos destinos coherentes con su zona (Norte: Boyacá, Santander, Costa y Cundinamarca
 * norte; Salitre: Tolima, Huila, Valle, Nariño, Caquetá, Meta, Eje Cafetero, Antioquia y Costa), cada uno de ida y de
 * vuelta (2 a 4 rutas), sobre la red de {@link SeedData#TRAMOS}: si la ciudad real no está en el catálogo se usa la
 * más cercana (Duitama, Sogamoso → Tunja; Zipaquirá, Guasca, Valle de Tenza → Chocontá; Chiquinquirá, Socorro →
 * Barbosa, San Gil; Neiva, Fusagasugá → Girardot; Caquetá → Ibagué; Pasto, Ipiales → Cali; Valledupar → Bosconia). La
 * parada de Bogotá apunta a la terminal de la empresa. Salidas: 2 o 3 diarias, a horas que no usa ninguna otra
 * empresa en la misma ruta. Precio: la tarifa de la ruta en el seed ±5–20 % según la empresa, y por franja
 * (mañana sin cambio, tarde +5 %, noche −10 %).
 */
final class SeedEmpresasBogota {

    /** Terminal del Norte y Terminal Salitre (ids fijos de la migración V8, con sus coordenadas). */
    static final UUID NORTE = UUID.fromString("7e0a0001-0000-4000-8000-000000000003");
    static final UUID SALITRE = UUID.fromString("7e0a0001-0000-4000-8000-000000000001");
    private static final Map<UUID, double[]> COORDENADAS = Map.of(
            NORTE, new double[] {4.7562, -74.0447},
            SALITRE, new double[] {4.6585, -74.1153});

    static final String BOGOTA = "Bogotá";

    /** Primera y última hora de salida posibles, cada 5 minutos. */
    private static final LocalTime PRIMERA = LocalTime.of(4, 0);
    private static final int MINUTOS_ENTRE_HORAS = 5;
    private static final int HORAS_POSIBLES = 240;

    private SeedEmpresasBogota() {
    }

    /** Una empresa real: NIT ficticio, terminal de salida en Bogotá, destinos y salidas diarias por ruta. */
    record Empresa(String key, String nombre, String nit, UUID terminal, List<String> destinos, int salidas,
                   int ajustePorcentaje) {
    }

    /** Una ruta de una de estas empresas, con sus horas de salida (ordenadas) y la terminal de Bogotá. */
    record Ruta(SeedData.RouteSeed ruta, UUID terminal, int empresa, int indice, List<LocalTime> salidas) {

        /** Precio de la salida: la tarifa de la empresa y el recargo o descuento de la franja. */
        BigDecimal precio(LocalTime hora) {
            return precioEmpresa(ruta.precioBase(), hora);
        }

        /** Placa del bus de cada salida: E + empresa (2) + ruta (1) + salida (1); los buses alternos usan U. */
        String placa(int slot) {
            return String.format("E%02d%d%d", empresa, indice, slot);
        }
    }

    static final List<Empresa> EMPRESAS = empresas();

    static final List<Ruta> RUTAS = rutas();

    private static List<Empresa> empresas() {
        String[][] norte = {
                {"autoboy", "Autoboy", "Tunja"},
                {"berlinas", "Berlinas del Fonce", "Bucaramanga", "San Gil"},
                {"coflonorte", "Coflonorte (Libertadores)", "Tunja"},
                {"cootransbol", "Cootransbol (Concorde)", "Barbosa"},
                {"cootranszipa", "Cootranszipa", "Chocontá"},
                {"copetran", "Copetran", "Bucaramanga", "Cartagena"},
                {"rapido-duitama", "El Rápido Duitama", "Tunja"},
                {"brasilia", "Expreso Brasilia", "Cartagena"},
                {"cundinamarca", "Expreso Cundinamarca", "Chocontá"},
                {"gaviota", "Expreso Gaviota", "Barrancabermeja"},
                {"gomez-villa", "Expreso Gómez Villa (Flota Rionegro)", "Bucaramanga"},
                {"comuneros", "Expreso Los Comuneros", "San Gil"},
                {"paz-del-rio", "Expreso Paz del Río", "Tunja"},
                {"aguila", "Flota Águila", "Barbosa"},
                {"ayacucho", "Flota Ayacucho", "Chocontá"},
                {"flota-boyaca", "Flota Boyacá", "Tunja"},
                {"macarena", "Flota La Macarena", "Tunja"},
                {"sugamuxi", "Flota Sugamuxi", "Tunja"},
                {"valle-de-tenza", "Flota Valle de Tenza", "Chocontá"},
                {"flota-zipa", "Flota Zipa", "Chocontá"},
                {"delfines", "Los Delfines", "Cartagena"},
                {"omega", "Omega", "Bosconia"},
                {"rapido-el-carmen", "Rápido El Carmen", "Aguachica"},
                {"alianza", "Transportes Alianza", "Chocontá"},
                {"arizona", "Transportes Arizona", "Barbosa"},
                {"guasca", "Transportes Guasca", "Chocontá"},
                {"la-esperanza", "Transportes La Esperanza", "Tunja"},
                {"la-verde", "Transportes La Verde", "Chocontá"},
                {"reina", "Transportes Reina", "Barbosa"},
                {"tisquesusa", "Transportes Tisquesusa", "Chocontá"}};
        String[][] salitre = {
                {"bolivariano", "Expreso Bolivariano", "Cali"},
                {"coomotor", "Coomotor", "Girardot"},
                {"flota-magdalena", "Flota Magdalena", "Ibagué"},
                {"rapido-ochoa", "Rápido Ochoa", "Medellín"},
                {"expreso-palmira", "Expreso Palmira", "Cali"},
                {"cootransfusa", "Cootransfusa", "Girardot"},
                {"cootranshuila", "Cootranshuila", "Girardot"},
                {"rapido-tolima", "Rápido Tolima", "Ibagué"},
                {"velotax", "Velotax", "Pereira"},
                {"la-gaitana", "Expreso La Gaitana", "Girardot"},
                {"ormeno", "Expreso Internacional Ormeño", "Cali"},
                {"transipiales", "Transipiales", "Cali"},
                {"autollanos", "Autollanos", "Villavicencio"},
                {"santa-fe", "Flota Santa Fe", "Medellín"},
                {"continental", "Continental Bus", "Ibagué"},
                {"taxis-verdes", "Taxis Verdes", "Armenia"},
                {"cootransmayo", "Cootransmayo", "Ibagué"},
                {"simon-bolivar", "Simón Bolívar", "Sincelejo"}};
        // Las más grandes tienen 3 salidas diarias por ruta (el total de viajes no pasa del doble del seed base).
        List<String> tresSalidas = List.of("brasilia", "bolivariano", "rapido-ochoa");
        List<Empresa> todas = new ArrayList<>();
        List<String[][]> grupos = List.of(norte, salitre);
        List<UUID> terminales = List.of(NORTE, SALITRE);
        for (int g = 0; g < grupos.size(); g++) {
            UUID terminal = terminales.get(g);
            for (String[] e : grupos.get(g)) {
                int k = todas.size();
                // ±5–20 %: la magnitud recorre 5..20 y el signo alterna, siempre igual para la misma empresa.
                int ajuste = (5 + (k * 7) % 16) * (k % 2 == 0 ? 1 : -1);
                todas.add(new Empresa(e[0], e[1], String.format("9012%05d-%d", k + 1, (k * 3) % 10), terminal,
                        List.of(e).subList(2, e.length), tresSalidas.contains(e[0]) ? 3 : 2, ajuste));
            }
        }
        return Collections.unmodifiableList(todas);
    }

    private static List<Ruta> rutas() {
        Map<String, BigDecimal> tarifas = new LinkedHashMap<>();
        SeedData.RUTAS.forEach(r -> tarifas.putIfAbsent(r.origen() + ">" + r.destino(), r.precioBase()));
        List<SeedData.RouteSeed> seeds = new ArrayList<>();
        List<Integer> deEmpresa = new ArrayList<>();
        for (int k = 0; k < EMPRESAS.size(); k++) {
            Empresa e = EMPRESAS.get(k);
            for (String destino : e.destinos()) {
                List<SeedData.StopSeed> ida = desdeBogota(destino, e.terminal());
                int minutos = ida.get(ida.size() - 1).minutosDesdeSalida();
                BigDecimal tarifa = tarifas.getOrDefault(BOGOTA + ">" + destino, SeedData.precioPorDuracion(minutos));
                BigDecimal precio = PricingRules.redondear(tarifa.multiply(BigDecimal.valueOf(100L + e.ajustePorcentaje()))
                        .divide(BigDecimal.valueOf(100)));
                BusType tipo = minutos >= 600 ? BusType.DOS_PISOS : BusType.UN_PISO;
                String llave = "e:" + e.key() + ":";
                seeds.add(new SeedData.RouteSeed(llave + "bog-" + slug(destino), e.key(), tipo, precio, ida));
                seeds.add(new SeedData.RouteSeed(llave + slug(destino) + "-bog", e.key(), tipo, precio,
                        SeedData.alReves(ida)));
                deEmpresa.add(k);
                deEmpresa.add(k);
            }
        }
        Map<String, List<LocalTime>> horas = horarios(seeds, deEmpresa);
        List<Ruta> rutas = new ArrayList<>();
        Map<Integer, Integer> indicePorEmpresa = new LinkedHashMap<>();
        for (int i = 0; i < seeds.size(); i++) {
            int k = deEmpresa.get(i);
            Empresa e = EMPRESAS.get(k);
            int indice = indicePorEmpresa.merge(k, 1, Integer::sum) - 1;
            rutas.add(new Ruta(seeds.get(i), e.terminal(), k, indice, horas.get(seeds.get(i).key())));
        }
        return Collections.unmodifiableList(rutas);
    }

    /** Bogotá → destino por el camino más corto; la parada de Bogotá queda en la terminal de la empresa. */
    static List<SeedData.StopSeed> desdeBogota(String destino, UUID terminal) {
        List<SeedData.StopSeed> camino = new ArrayList<>(SeedData.camino(BOGOTA, destino));
        double[] c = COORDENADAS.get(terminal);
        camino.set(0, new SeedData.StopSeed(BOGOTA, c[0], c[1], 0));
        return camino;
    }

    /**
     * Horas de salida de cada ruta. Por cada par (origen, destino) se reparten entre todas sus empresas, en turnos,
     * horas a lo largo del día (de 4:00 a 23:55, cada 5 minutos, sin las de {@link SeedData#SALIDAS} que usan las
     * empresas ficticias): ninguna hora se repite en el mismo par y las de una empresa quedan separadas.
     */
    static Map<String, List<LocalTime>> horarios(List<SeedData.RouteSeed> seeds, List<Integer> deEmpresa) {
        List<LocalTime> posibles = new ArrayList<>();
        for (int i = 0; i < HORAS_POSIBLES; i++) {
            LocalTime h = PRIMERA.plusMinutes((long) i * MINUTOS_ENTRE_HORAS);
            if (!SeedData.SALIDAS.contains(h)) {
                posibles.add(h);
            }
        }
        Map<String, List<SeedData.RouteSeed>> porPar = new LinkedHashMap<>();
        Map<String, Integer> salidas = new LinkedHashMap<>();
        for (int i = 0; i < seeds.size(); i++) {
            SeedData.RouteSeed r = seeds.get(i);
            porPar.computeIfAbsent(r.origen() + ">" + r.destino(), p -> new ArrayList<>()).add(r);
            salidas.put(r.key(), EMPRESAS.get(deEmpresa.get(i)).salidas());
        }
        Map<String, List<LocalTime>> horas = new LinkedHashMap<>();
        for (List<SeedData.RouteSeed> rutas : porPar.values()) {
            int total = rutas.stream().mapToInt(r -> salidas.get(r.key())).sum();
            int turno = 0;
            for (int vuelta = 0; turno < total; vuelta++) {
                for (SeedData.RouteSeed r : rutas) {
                    if (vuelta < salidas.get(r.key())) {
                        int indice = (int) ((turno + 0.5) * posibles.size() / total);
                        horas.computeIfAbsent(r.key(), x -> new ArrayList<>()).add(posibles.get(indice));
                        turno++;
                    }
                }
            }
        }
        horas.values().forEach(Collections::sort);
        return horas;
    }

    /** Franja de la salida: mañana (5:00–11:59) sin cambio, tarde (12:00–17:59) +5 %, noche −10 %; a 100 COP. */
    static BigDecimal precioEmpresa(BigDecimal tarifaEmpresa, LocalTime hora) {
        int h = hora.getHour();
        String factor;
        if (h >= 5 && h < 12) {
            factor = "1.00";
        } else if (h >= 12 && h < 18) {
            factor = "1.05";
        } else {
            factor = "0.90";
        }
        return PricingRules.redondear(tarifaEmpresa.multiply(new BigDecimal(factor)));
    }

    /** "San Gil" → "san-gil" (llaves de las rutas). */
    static String slug(String ciudad) {
        String sinTildes = Normalizer.normalize(ciudad, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).replace(' ', '-');
    }
}
