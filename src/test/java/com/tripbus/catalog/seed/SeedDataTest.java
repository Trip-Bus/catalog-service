package com.tripbus.catalog.seed;

import com.tripbus.catalog.service.RouteValidator;
import com.tripbus.catalog.web.dto.StopRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    /**
     * US-117: cada bus del seed hace siempre la misma ruta a la misma hora. Si la ruta más el alistamiento no
     * cabe en 24 h, los días impares van en un bus alterno: cada bus sale cada 48 h y ahí sí cabe.
     */
    @Test
    void ningunBusDelSeedTieneViajesCruzados() {
        Duration margen = Duration.ofMinutes(30);
        int largas = 0;
        for (SeedData.RouteSeed r : SeedData.RUTAS) {
            long ocupado = r.duracionMinutos() + margen.toMinutes();
            if (SeedData.necesitaBusAlterno(r, margen)) {
                largas++;
                assertThat(ocupado).as(r.key()).isGreaterThan(24 * 60).isLessThanOrEqualTo(48 * 60);
            } else {
                assertThat(ocupado).as(r.key()).isLessThanOrEqualTo(24 * 60);
            }
        }
        // Cartagena <-> Villavicencio (25 h 30 min) al menos.
        assertThat(largas).isGreaterThanOrEqualTo(2);
    }

    @Test
    void losDiasImparesAlternan() {
        LocalDate dia = LocalDate.of(2026, 10, 21);
        assertThat(SeedData.esDiaImpar(dia)).isNotEqualTo(SeedData.esDiaImpar(dia.plusDays(1)));
        assertThat(SeedData.esDiaImpar(LocalDate.of(2000, 1, 1))).isFalse();
        assertThat(SeedData.esDiaImpar(LocalDate.of(2000, 1, 2))).isTrue();
        assertThat(SeedData.esDiaImpar(LocalDate.of(1999, 12, 31))).isTrue();
    }

    /** Las rutas del seed cumplen las mismas reglas que exige POST /routes (US-114). */
    @Test
    void lasRutasDelSeedSonValidasParaLaApi() {
        for (SeedData.RouteSeed r : SeedData.RUTAS) {
            List<StopRequest> paradas = new ArrayList<>();
            for (int i = 0; i < r.paradas().size(); i++) {
                SeedData.StopSeed p = r.paradas().get(i);
                paradas.add(new StopRequest(i + 1, p.nombre(), p.lat(), p.lng(), p.minutosDesdeSalida()));
            }
            assertThat(RouteValidator.validar(r.origen(), r.destino(), paradas)).as(r.key())
                    .isEqualTo(r.duracionMinutos());
        }
    }
}
