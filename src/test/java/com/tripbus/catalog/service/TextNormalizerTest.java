package com.tripbus.catalog.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextNormalizerTest {

    @Test
    void quitaTildesMayusculasYEspaciosExtra() {
        assertThat(TextNormalizer.normalize("  Bogotá ")).isEqualTo("bogota");
        assertThat(TextNormalizer.normalize("MEDELLÍN")).isEqualTo("medellin");
        assertThat(TextNormalizer.normalize("San   Gil")).isEqualTo("san gil");
        assertThat(TextNormalizer.normalize(null)).isEmpty();
    }
}
