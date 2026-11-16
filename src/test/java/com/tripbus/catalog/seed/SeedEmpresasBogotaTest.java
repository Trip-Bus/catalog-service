package com.tripbus.catalog.seed;

import com.tripbus.catalog.service.RouteValidator;
import com.tripbus.catalog.web.dto.StopRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Empresas de las terminales de Bogotá: rutas, horarios, precios e ids deterministas. */
class SeedEmpresasBogotaTest {

    @Test
    void sonLasEmpresasDeLaTerminalDelNorteYDeSalitreConNitUnico() {
        assertThat(SeedEmpresasBogota.EMPRESAS).hasSize(48);
        assertThat(SeedEmpresasBogota.EMPRESAS).filteredOn(e -> e.terminal().equals(SeedEmpresasBogota.NORTE))
                .hasSize(30).extracting(SeedEmpresasBogota.Empresa::nombre)
                .contains("Autoboy", "Copetran", "Expreso Brasilia", "Transportes Tisquesusa");
        assertThat(SeedEmpresasBogota.EMPRESAS).filteredOn(e -> e.terminal().equals(SeedEmpresasBogota.SALITRE))
                .hasSize(18).extracting(SeedEmpresasBogota.Empresa::nombre)
                .contains("Expreso Bolivariano", "Rápido Ochoa", "Simón Bolívar");
        Set<String> llaves = new HashSet<>();
        Set<String> nits = new HashSet<>();
        Set<String> nombres = new HashSet<>();
        SeedData.EMPRESAS.forEach(e -> {
            llaves.add(e.key());
            nits.add(e.nit().split("-")[0]);
            nombres.add(e.nombre());
        });
        for (SeedEmpresasBogota.Empresa e : SeedEmpresasBogota.EMPRESAS) {
            assertThat(llaves.add(e.key())).as(e.key()).isTrue();
            assertThat(nits.add(e.nit().split("-")[0])).as(e.nit()).isTrue();
            assertThat(nombres.add(e.nombre())).as(e.nombre()).isTrue();
            assertThat(Math.abs(e.ajustePorcentaje())).as(e.key()).isBetween(5, 20);
            assertThat(e.salidas()).isBetween(2, 3);
        }
    }

    @Test
    void cadaEmpresaTieneDeDosACuatroRutasDesdeYHaciaBogotaEnSuTerminal() {
        Map<Integer, Integer> rutasPorEmpresa = new HashMap<>();
        Set<String> llaves = new HashSet<>();
        SeedData.RUTAS.forEach(r -> llaves.add(r.key()));
        for (SeedEmpresasBogota.Ruta r : SeedEmpresasBogota.RUTAS) {
            rutasPorEmpresa.merge(r.empresa(), 1, Integer::sum);
            assertThat(llaves.add(r.ruta().key())).as(r.ruta().key()).isTrue();
            SeedEmpresasBogota.Empresa e = SeedEmpresasBogota.EMPRESAS.get(r.empresa());
            assertThat(r.ruta().companyKey()).isEqualTo(e.key());
            assertThat(r.terminal()).isEqualTo(e.terminal());
            assertThat(List.of(r.ruta().origen(), r.ruta().destino())).contains(SeedEmpresasBogota.BOGOTA);
            assertThat(r.salidas()).hasSize(e.salidas()).isSorted();
        }
        assertThat(rutasPorEmpresa).hasSize(48);
        assertThat(rutasPorEmpresa.values()).allSatisfy(n -> assertThat(n).isBetween(2, 4));
        SeedEmpresasBogota.Ruta copetranIda = SeedEmpresasBogota.RUTAS.stream()
                .filter(r -> r.ruta().key().equals("e:copetran:bog-bucaramanga")).findFirst().orElseThrow();
        // La parada de Bogotá está en la Terminal del Norte (sus coordenadas, no unas nuevas).
        assertThat(copetranIda.ruta().paradas().get(0).lat()).isEqualTo(4.7562);
        assertThat(copetranIda.ruta().paradas()).extracting(SeedData.StopSeed::nombre)
                .containsExactly("Bogotá", "Chocontá", "Tunja", "Barbosa", "San Gil", "Bucaramanga");
        assertThat(copetranIda.placa(1)).isEqualTo("E05" + copetranIda.indice() + "1");
    }

    @Test
    void ningunaHoraDeSalidaSeRepiteEnLaMismaRuta() {
        Map<String, Set<LocalTime>> porPar = new HashMap<>();
        for (SeedData.RouteSeed r : SeedData.RUTAS) {
            porPar.computeIfAbsent(r.origen() + ">" + r.destino(), p -> new HashSet<>()).addAll(SeedData.SALIDAS);
        }
        for (SeedEmpresasBogota.Ruta r : SeedEmpresasBogota.RUTAS) {
            Set<LocalTime> usadas = porPar.computeIfAbsent(r.ruta().origen() + ">" + r.ruta().destino(),
                    p -> new HashSet<>());
            for (LocalTime h : r.salidas()) {
                assertThat(usadas.add(h)).as(r.ruta().key() + " " + h).isTrue();
            }
        }
    }

    @Test
    void elPrecioEsLaTarifaDeLaRutaConElAjusteDeLaEmpresaYLaFranja() {
        Map<String, BigDecimal> tarifas = new HashMap<>();
        SeedData.RUTAS.forEach(r -> tarifas.putIfAbsent(r.origen() + ">" + r.destino(), r.precioBase()));
        for (SeedEmpresasBogota.Ruta r : SeedEmpresasBogota.RUTAS) {
            SeedEmpresasBogota.Empresa e = SeedEmpresasBogota.EMPRESAS.get(r.empresa());
            String ida = SeedEmpresasBogota.BOGOTA + ">" + (r.ruta().origen().equals(SeedEmpresasBogota.BOGOTA)
                    ? r.ruta().destino() : r.ruta().origen());
            BigDecimal base = tarifas.getOrDefault(ida, null);
            if (base != null) {
                double variacion = r.ruta().precioBase().doubleValue() / base.doubleValue() - 1;
                assertThat(Math.abs(variacion)).as(r.ruta().key()).isBetween(0.04, 0.21);
                assertThat(Math.signum(variacion)).isEqualTo(Math.signum(e.ajustePorcentaje()));
            }
            assertThat(r.ruta().precioBase().remainder(BigDecimal.valueOf(100))).isEqualByComparingTo("0");
        }
        BigDecimal tarifa = new BigDecimal("50000");
        assertThat(SeedEmpresasBogota.precioEmpresa(tarifa, LocalTime.of(7, 0))).isEqualByComparingTo("50000");
        assertThat(SeedEmpresasBogota.precioEmpresa(tarifa, LocalTime.of(15, 30))).isEqualByComparingTo("52500");
        assertThat(SeedEmpresasBogota.precioEmpresa(tarifa, LocalTime.of(21, 0))).isEqualByComparingTo("45000");
        assertThat(SeedEmpresasBogota.precioEmpresa(tarifa, LocalTime.of(4, 15))).isEqualByComparingTo("45000");
        assertThat(SeedEmpresasBogota.slug("San Gil")).isEqualTo("san-gil");
        assertThat(SeedEmpresasBogota.slug("Chocontá")).isEqualTo("choconta");
    }

    /** Para no cargar Seat Inventory: no más del doble de los viajes diarios del seed base. */
    @Test
    void noGeneraMasDelDobleDeViajesQueElSeedBase() {
        int base = SeedData.RUTAS.size() * SeedData.SALIDAS.size();
        int nuevos = SeedEmpresasBogota.RUTAS.stream().mapToInt(r -> r.salidas().size()).sum();
        assertThat(nuevos).isPositive().isLessThanOrEqualTo(base);
        assertThat(BigDecimal.valueOf(nuevos + base).divide(BigDecimal.valueOf(base), 2, RoundingMode.HALF_UP))
                .isLessThanOrEqualTo(new BigDecimal("2.00"));
    }

    /** Las rutas cumplen las reglas de POST /routes y sus buses no se cruzan (US-114, US-117). */
    @Test
    void lasRutasSonValidasYSusBusesNoSeCruzan() {
        Duration margen = Duration.ofMinutes(30);
        for (SeedEmpresasBogota.Ruta ruta : SeedEmpresasBogota.RUTAS) {
            SeedData.RouteSeed r = ruta.ruta();
            List<StopRequest> paradas = new ArrayList<>();
            for (int i = 0; i < r.paradas().size(); i++) {
                SeedData.StopSeed p = r.paradas().get(i);
                paradas.add(new StopRequest(i + 1, p.nombre(), p.lat(), p.lng(), p.minutosDesdeSalida()));
            }
            assertThat(RouteValidator.validar(r.origen(), r.destino(), paradas)).as(r.key())
                    .isEqualTo(r.duracionMinutos());
            assertThat(r.duracionMinutos() + margen.toMinutes()).as(r.key()).isLessThanOrEqualTo(48 * 60L);
        }
    }

    @Test
    void lasRutasSonSiempreLasMismas() {
        assertThat(SeedEmpresasBogota.RUTAS.get(0).ruta().key()).isEqualTo("e:autoboy:bog-tunja");
        assertThat(SeedEmpresasBogota.RUTAS.get(1).ruta().key()).isEqualTo("e:autoboy:tunja-bog");
        assertThat(SeedEmpresasBogota.horarios(List.of(SeedData.RUTAS.get(7)), List.of(0)))
                .containsKey("bog-tun");
    }
}
