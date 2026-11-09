package com.tripbus.catalog.repository;

import com.tripbus.catalog.service.SearchMode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** US-138: qué es recorrido completo y qué modo de búsqueda pide cada valor de tramos. */
class TramoParadaTest {

    private static final UUID RUTA = UUID.randomUUID();

    @Test
    void soloDeLaPrimeraALaUltimaParadaEsElRecorridoCompleto() {
        assertThat(new TramoParada(RUTA, 1, 4, 0, 540, 540).esCompleto()).isTrue();
        assertThat(new TramoParada(RUTA, 2, 4, 210, 540, 540).esCompleto()).isFalse();
        assertThat(new TramoParada(RUTA, 1, 3, 0, 330, 540).esCompleto()).isFalse();
    }

    @Test
    void modoDeBusqueda() {
        assertThat(SearchMode.de(null)).isEqualTo(SearchMode.AUTO);
        assertThat(SearchMode.de(true)).isEqualTo(SearchMode.TODOS);
        assertThat(SearchMode.de(false)).isEqualTo(SearchMode.DIRECTOS);
    }
}
