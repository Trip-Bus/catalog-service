package com.tripbus.catalog.domain;

import java.time.DayOfWeek;

/** Días de la semana en español para la programación de viajes (US-116). */
public enum DiaSemana {
    LUNES(DayOfWeek.MONDAY),
    MARTES(DayOfWeek.TUESDAY),
    MIERCOLES(DayOfWeek.WEDNESDAY),
    JUEVES(DayOfWeek.THURSDAY),
    VIERNES(DayOfWeek.FRIDAY),
    SABADO(DayOfWeek.SATURDAY),
    DOMINGO(DayOfWeek.SUNDAY);

    private final DayOfWeek dayOfWeek;

    DiaSemana(DayOfWeek dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public DayOfWeek dayOfWeek() {
        return dayOfWeek;
    }
}
