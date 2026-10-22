package com.tripbus.catalog.seed;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SeedDataTest {

    @Test
    void hayUnaRutaEntreCadaParDeCiudadesEnLosDosSentidos() {
        Set<String> pares = new HashSet<>();
        for (SeedData.RouteSeed r : SeedData.RUTAS) {
            pares.add(r.origen() + ">" + r.destino());
        }
        for (String o : SeedData.CIUDADES) {
            for (String d : SeedData.CIUDADES) {
                if (!o.equals(d)) {
                    assertThat(pares).as(o + " → " + d).contains(o + ">" + d);
                }
            }
        }
        assertThat(SeedData.RUTAS).hasSize(42);
    }

    @Test
    void lasLlavesSonUnicasYLasRutasOriginalesNoCambian() {
        Set<String> llaves = new HashSet<>();
        SeedData.RUTAS.forEach(r -> assertThat(llaves.add(r.key())).as(r.key()).isTrue());
        assertThat(SeedData.RUTAS.get(0).key()).isEqualTo("bog-med");
        assertThat(SeedData.RUTAS.get(7).key()).isEqualTo("bog-tun");
    }

    @Test
    void lasParadasAvanzanEnElTiempoYElPrecioCreceConLaDuracion() {
        for (SeedData.RouteSeed r : SeedData.RUTAS) {
            for (int i = 1; i < r.paradas().size(); i++) {
                assertThat(r.paradas().get(i).minutosDesdeSalida()).isGreaterThan(r.paradas().get(i - 1).minutosDesdeSalida());
            }
        }
        var medVil = SeedData.camino("Medellín", "Villavicencio");
        assertThat(medVil).extracting(SeedData.StopSeed::nombre)
                .containsExactly("Medellín", "Puerto Triunfo", "Honda", "Bogotá", "Chipaque", "Villavicencio");
        assertThat(SeedData.precioPorDuracion(180).intValue()).isLessThan(SeedData.precioPorDuracion(720).intValue());
    }
}
