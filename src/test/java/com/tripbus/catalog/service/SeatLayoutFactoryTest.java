package com.tripbus.catalog.service;

import com.tripbus.catalog.domain.BusType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeatLayoutFactoryTest {

    @Test
    void busDeUnPisoTiene40AsientosUnicosConPasilloYBano() {
        List<String> asientos = asientosDelLayout(BusType.UN_PISO);
        assertThat(asientos).hasSize(40).doesNotHaveDuplicates();
        assertThat(asientos).isEqualTo(SeatLayoutFactory.seatNumbers(BusType.UN_PISO));
        assertThat(tipos(BusType.UN_PISO)).contains("PASILLO", "BANO", "CONDUCTOR");
    }

    @Test
    void busDeDosPisosTiene60AsientosRepartidosEnDosPisos() {
        Map<String, Object> layout = SeatLayoutFactory.layout(BusType.DOS_PISOS);
        assertThat((List<?>) layout.get("pisos")).hasSize(2);
        List<String> asientos = asientosDelLayout(BusType.DOS_PISOS);
        assertThat(asientos).hasSize(60);
        assertThat(new HashSet<>(asientos)).hasSize(60);
        assertThat(tipos(BusType.DOS_PISOS)).contains("ESCALERA");
    }

    @SuppressWarnings("unchecked")
    private static List<String> asientosDelLayout(BusType tipo) {
        List<String> asientos = new ArrayList<>();
        for (Object piso : (List<Object>) SeatLayoutFactory.layout(tipo).get("pisos")) {
            for (Object celda : (List<Object>) ((Map<String, Object>) piso).get("celdas")) {
                Object asiento = ((Map<String, Object>) celda).get("asiento");
                if (asiento != null) {
                    asientos.add((String) asiento);
                }
            }
        }
        return asientos;
    }

    @SuppressWarnings("unchecked")
    private static List<String> tipos(BusType tipo) {
        List<String> tipos = new ArrayList<>();
        for (Object piso : (List<Object>) SeatLayoutFactory.layout(tipo).get("pisos")) {
            for (Object celda : (List<Object>) ((Map<String, Object>) piso).get("celdas")) {
                tipos.add((String) ((Map<String, Object>) celda).get("tipo"));
            }
        }
        return tipos;
    }
}
