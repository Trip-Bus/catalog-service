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

    private static TripSearchResult conOferta(Instant fin) {
        Instant salida = AHORA.plus(1, ChronoUnit.DAYS);
        return new TripSearchResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Expreso Andino",
                "Bogotá", "Medellín", salida, salida.plus(9, ChronoUnit.HOURS), 540, new BigDecimal("95000"),
                "COP", "DOS_PISOS", 40, null, null,
                new OfertaResumen(UUID.randomUUID(), new BigDecimal("49900"), fin), false);
    }
}
