package com.tripbus.catalog.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** US-138: reglas de precio con los números del README. */
class PricingRulesTest {

    private static final BigDecimal BASE = new BigDecimal("95000");

    @Test
    void redondeaAlCienMasCercano() {
        assertThat(PricingRules.redondear(new BigDecimal("58055.56"))).isEqualByComparingTo("58100");
        assertThat(PricingRules.redondear(new BigDecimal("58049.99"))).isEqualByComparingTo("58000");
        assertThat(PricingRules.redondear(new BigDecimal("58050"))).isEqualByComparingTo("58100");
    }

    @Test
    void elRecorridoCompletoValeLaBaseAunqueHayaPrecioExacto() {
        assertThat(PricingRules.tramo(BASE, 0, 540, 540, new BigDecimal("1000"), 30)).isEqualByComparingTo(BASE);
    }

    @Test
    void tramoProporcionalALosMinutos() {
        // Honda (210) → Medellín (540) de 540 minutos: 95.000 × 330 / 540 = 58.055,56 → 58.100.
        assertThat(PricingRules.tramo(BASE, 210, 540, 540, null, 30)).isEqualByComparingTo("58100");
        // Desde la primera parada pero sin llegar al final también es tramo.
        assertThat(PricingRules.tramo(BASE, 0, 210, 540, null, 30)).isEqualByComparingTo("36900");
    }

    @Test
    void nuncaMenosDelPorcentajeMinimoDeLaBase() {
        // Bogotá (0) → Chipaque (45) de 180: 38.000 × 45 / 180 = 9.500, pero el 30 % es 11.400.
        assertThat(PricingRules.tramo(new BigDecimal("38000"), 0, 45, 180, null, 30)).isEqualByComparingTo("11400");
        // El mínimo se redondea hacia arriba a 100: 30 % de 38.050 = 11.415 → 11.500.
        assertThat(PricingRules.tramo(new BigDecimal("38050"), 0, 45, 180, null, 30)).isEqualByComparingTo("11500");
    }

    @Test
    void nuncaMasQueLaBase() {
        // 160 × 170 / 180 = 151,1 → 200 al redondear, pero nunca más que la base.
        assertThat(PricingRules.tramo(new BigDecimal("160"), 0, 170, 180, null, 30)).isEqualByComparingTo("160");
    }

    @Test
    void elPrecioExactoReemplazaAlProporcional() {
        assertThat(PricingRules.tramo(BASE, 210, 540, 540, new BigDecimal("50000"), 30)).isEqualByComparingTo("50000");
    }

    @Test
    void temporadaYDescuentoRedondeadosACien() {
        assertThat(PricingRules.conTemporada(new BigDecimal("58100"), new BigDecimal("1.2"))).isEqualByComparingTo("69700");
        assertThat(PricingRules.conTemporada(new BigDecimal("58100"), BigDecimal.ONE)).isEqualByComparingTo("58100");
        assertThat(PricingRules.conDescuento(new BigDecimal("69700"), new BigDecimal("20"))).isEqualByComparingTo("55800");
        assertThat(PricingRules.conDescuento(new BigDecimal("69700"), BigDecimal.ZERO)).isEqualByComparingTo("69700");
        assertThat(PricingRules.conDescuento(new BigDecimal("69700"), new BigDecimal("100"))).isEqualByComparingTo("0");
    }
}
