package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.DiaSemana;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItineraryPlanningServiceTest {

    private static final LocalDate LUNES = LocalDate.of(2026, 11, 2);

    @Test
    void soloLasFechasDeLosDiasPedidos() {
        var fechas = ItineraryPlanningService.fechas(LUNES, LUNES.plusDays(13), Set.of(DiaSemana.LUNES, DiaSemana.VIERNES));
        assertThat(fechas).containsExactly(LUNES, LUNES.plusDays(4), LUNES.plusDays(7), LUNES.plusDays(11));
    }

    @Test
    void todosLosDiasDeLaSemanaSeMapeanBien() {
        var fechas = ItineraryPlanningService.fechas(LUNES, LUNES.plusDays(6), EnumSet.allOf(DiaSemana.class));
        assertThat(fechas).hasSize(7);
        assertThat(fechas.get(6).getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(DiaSemana.MIERCOLES.dayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
    }

    @Test
    void maximo90DiasYHastaNoAnteriorADesde() {
        assertThat(ItineraryPlanningService.fechas(LUNES, LUNES.plusDays(89), EnumSet.allOf(DiaSemana.class)))
                .hasSize(90);
        assertThatThrownBy(() -> ItineraryPlanningService.fechas(LUNES, LUNES.plusDays(90), Set.of(DiaSemana.LUNES)))
                .isInstanceOf(ApiException.class).hasMessageContaining("90");
        assertThatThrownBy(() -> ItineraryPlanningService.fechas(LUNES, LUNES.minusDays(1), Set.of(DiaSemana.LUNES)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("INVALID_SCHEDULE");
    }

    @Test
    void sinCoincidenciasNoHayFechas() {
        assertThat(ItineraryPlanningService.fechas(LUNES, LUNES.plusDays(1), Set.of(DiaSemana.DOMINGO))).isEmpty();
    }
}
