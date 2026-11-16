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

    @Test
    void elPrecioDesdeAplicaTemporadaYDescuentoDeAdultoSobreElPrecioEfectivo() {
        TripSearchResult viaje = conOferta(AHORA.minus(1, ChronoUnit.MINUTES)).sinOfertaVencida(AHORA);

        // Sin tarifa conocida es el precio efectivo.
        assertThat(viaje.precioDesde()).isEqualByComparingTo("95000");
        // Temporada 1,15: 109.250 → 109.300.
        assertThat(viaje.conTarifa(new BigDecimal("1.15"), null).precioDesde()).isEqualByComparingTo("109300");
        // Solo descuento de 10 %: 85.500.
        assertThat(viaje.conTarifa(null, new BigDecimal("10")).precioDesde()).isEqualByComparingTo("85500");
        // La oferta activa también lleva la temporada: 49.900 × 1,2 = 59.880 → 59.900.
        assertThat(conOferta(null).conTarifa(new BigDecimal("1.2"), BigDecimal.ZERO).precioDesde())
                .isEqualByComparingTo("59900");
    }

    @Test
    void alVencerLaOfertaElTramoVuelveASuPrecioSinOfertaYConservaLaTarifa() {
        TripSearchResult viaje = conOferta(AHORA.minus(1, ChronoUnit.MINUTES)).conTarifa(new BigDecimal("1.1"), null);
        TripSearchResult tramo = viaje.conTramo(2, 4, viaje.fechaSalida().plus(210, ChronoUnit.MINUTES),
                viaje.fechaSalida().plus(540, ChronoUnit.MINUTES), new BigDecimal("30500"), new BigDecimal("58100"));

        assertThat(tramo.precioDesde()).isEqualByComparingTo("33600");
        TripSearchResult vencida = tramo.sinOfertaVencida(AHORA).withDisponibilidad(3);
        assertThat(vencida.precio()).isEqualByComparingTo("58100");
        assertThat(vencida.precioTramoSinOferta()).isEqualByComparingTo("58100");
        assertThat(vencida.multiplicadorTemporada()).isEqualByComparingTo("1.1");
        // 58.100 × 1,1 = 63.910 → 63.900.
        assertThat(vencida.precioDesde()).isEqualByComparingTo("63900");
    }

    private static TripSearchResult conOferta(Instant fin) {
        Instant salida = AHORA.plus(1, ChronoUnit.DAYS);
        return new TripSearchResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Expreso Andino",
                "Bogotá", "Medellín", salida, salida.plus(9, ChronoUnit.HOURS), 540, new BigDecimal("95000"),
                "COP", "DOS_PISOS", 40, null, null,
                new OfertaResumen(UUID.randomUUID(), new BigDecimal("49900"), fin), false);
    }
}
