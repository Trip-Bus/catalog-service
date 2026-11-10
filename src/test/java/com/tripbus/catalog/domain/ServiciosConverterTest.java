package com.tripbus.catalog.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** US-113: los servicios se guardan como texto, en el orden del enum. */
class ServiciosConverterTest {

    private final ServiciosConverter converter = new ServiciosConverter();

    @Test
    void guardaEnElOrdenDelEnum() {
        assertThat(converter.convertToDatabaseColumn(Set.of(ServicioBus.TV, ServicioBus.WIFI))).isEqualTo("WIFI,TV");
        assertThat(converter.convertToDatabaseColumn(Set.of())).isEmpty();
        assertThat(converter.convertToDatabaseColumn(null)).isEmpty();
    }

    @Test
    void leeIgnorandoEspaciosYVacios() {
        assertThat(converter.convertToEntityAttribute(" WIFI, ,BANO,")).isEqualTo(EnumSet.of(ServicioBus.WIFI,
                ServicioBus.BANO));
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    }
}
