package com.tripbus.catalog.web.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TripSearchResultTest {

    private static final Instant AHORA = Instant.parse("2026-10-12T12:00:00Z");

    @Test
    void ofertaVencidaSeQuitaSinPerderLaDisponibilidad() {
        TripSearchResult r = conOferta(AHORA.minus(1, ChronoUnit.MINUTES)).withDisponibilidad(5);

        TripSearchResult sinOferta = r.sinOfertaVencida(AHORA);

        assertThat(sinOferta.ofertaActiva()).isNull();
        assertThat(sinOferta.asientosDisponibles()).isEqualTo(5);
        assertThat(sinOferta.agotado()).isFalse();
        assertThat(sinOferta.disponibilidadConocida()).isTrue();
    }

    @Test
    void ofertaVigenteSeConserva() {
        TripSearchResult r = conOferta(AHORA.plus(1, ChronoUnit.HOURS));

        assertThat(r.sinOfertaVencida(AHORA)).isSameAs(r);
    }

    @Test
    void elRecorridoCompletoUsaLaSalidaLaDuracionYElPrecioDelViaje() {
        TripSearchResult sinOferta = conOferta(AHORA.minus(1, ChronoUnit.MINUTES)).sinOfertaVencida(AHORA);
        TripSearchResult conOferta = conOferta(null);

        assertThat(sinOferta.esTramo()).isFalse();
        assertThat(sinOferta.salidaEfectiva()).isEqualTo(sinOferta.fechaSalida());
        assertThat(sinOferta.duracionEfectiva()).isEqualTo(540);
        assertThat(sinOferta.precioEfectivo()).isEqualByComparingTo("95000");
        assertThat(conOferta.precioEfectivo()).isEqualByComparingTo("49900");
        assertThat(conOferta.sinOfertaVencida(AHORA)).isSameAs(conOferta);
        assertThat(conOferta.servicios()).isEmpty();
    }

    @Test
    void unTramoUsaSuSalidaSuDuracionYSuPrecioYLosConserva() {
        TripSearchResult viaje = conOferta(AHORA.minus(1, ChronoUnit.MINUTES));
        Instant sube = viaje.fechaSalida().plus(210, ChronoUnit.MINUTES);
        TripSearchResult tramo = viaje.conTramo(2, 4, sube, viaje.fechaSalida().plus(540, ChronoUnit.MINUTES),
                new BigDecimal("58100"));

        assertThat(tramo.esTramo()).isTrue();
        assertThat(tramo.salidaEfectiva()).isEqualTo(sube);
        assertThat(tramo.duracionEfectiva()).isEqualTo(330);
        assertThat(tramo.precioEfectivo()).isEqualByComparingTo("58100");
        TripSearchResult sinOferta = tramo.sinOfertaVencida(AHORA).withDisponibilidad(null);
        assertThat(sinOferta.ofertaActiva()).isNull();
        assertThat(sinOferta.fromOrden()).isEqualTo(2);
        assertThat(sinOferta.toOrden()).isEqualTo(4);
        assertThat(sinOferta.precio()).isEqualByComparingTo("58100");
        assertThat(sinOferta.disponibilidadConocida()).isFalse();
        assertThat(sinOferta.agotado()).isNull();
    }

    private static TripSearchResult conOferta(Instant fin) {
        Instant salida = AHORA.plus(1, ChronoUnit.DAYS);
        return new TripSearchResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Expreso Andino",
                "Bogotá", "Medellín", salida, salida.plus(9, ChronoUnit.HOURS), 540, new BigDecimal("95000"),
                "COP", "DOS_PISOS", 40, null, null,
                new OfertaResumen(UUID.randomUUID(), new BigDecimal("49900"), fin), false);
    }
}
