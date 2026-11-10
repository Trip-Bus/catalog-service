package com.tripbus.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.catalog.common.events.EventPublisher;
import com.tripbus.catalog.common.observability.CatalogMetrics;
import com.tripbus.catalog.domain.Asiento;
import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.domain.SeatMap;
import com.tripbus.catalog.events.CatalogEvents;
import com.tripbus.catalog.repository.SeatMapRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-141: los asientos con su categoría en ItineraryPublished y en las respuestas del mapa. */
class ItineraryPublisherTest {

    private static final String MAPA = """
            {"tipo":"UN_PISO","capacidad":2,"pisos":[{"piso":1,"filas":2,"columnas":3,"celdas":[
              {"fila":0,"columna":1,"tipo":"CONDUCTOR"},
              {"fila":1,"columna":1,"tipo":"ASIENTO","asiento":"1","categoria":"PREMIUM"},
              {"fila":1,"columna":3,"tipo":"ASIENTO","asiento":"2"}]}]}""";

    private final ObjectMapper mapper = new ObjectMapper();
    private final EventPublisher events = mock(EventPublisher.class);
    private final SeatMapRepository seatMaps = mock(SeatMapRepository.class);
    private final ItineraryPublisher publisher = new ItineraryPublisher(events, seatMaps, mapper,
            CatalogMetrics.enMemoria());

    @Test
    void asientosDelMapaConSuCategoriaOEstandar() {
        Bus bus = bus(BusType.UN_PISO);
        when(seatMaps.findByBusId(bus.getId())).thenReturn(Optional.of(new SeatMap(UUID.randomUUID(), bus.getId(), MAPA)));

        assertThat(publisher.asientos(bus)).containsExactly(new Asiento("1", "PREMIUM"), new Asiento("2", "ESTANDAR"));
    }

    @Test
    void sinMapaGuardadoUsaElLayoutPorDefectoEnEstandar() {
        List<Asiento> asientos = publisher.asientos(bus(BusType.DOS_PISOS));

        assertThat(asientos).hasSize(60).allSatisfy(a -> assertThat(a.categoria()).isEqualTo("ESTANDAR"));
        assertThat(ItineraryPublisher.numeros(asientos)).startsWith("1", "2");
    }

    @Test
    void completaLasCategoriasQueFaltanSinTocarLasDemas() throws Exception {
        JsonNode mapa = ItineraryPublisher.conCategorias(mapper.readTree(MAPA));
        JsonNode celdas = mapa.get("pisos").get(0).get("celdas");

        assertThat(celdas.get(0).has("categoria")).isFalse();
        assertThat(celdas.get(1).get("categoria").asText()).isEqualTo("PREMIUM");
        assertThat(celdas.get(2).get("categoria").asText()).isEqualTo("ESTANDAR");
        assertThat(ItineraryPublisher.seatNumbers(mapa)).containsExactly("1", "2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicaUnEventoPorViajeConSeatsYSeatNumbersLeyendoElMapaUnaVez() {
        Bus bus = bus(BusType.UN_PISO);
        when(seatMaps.findByBusId(bus.getId())).thenReturn(Optional.of(new SeatMap(UUID.randomUUID(), bus.getId(), MAPA)));
        Route ruta = new Route(UUID.randomUUID(), new Company(UUID.randomUUID(), "E", "9"), "Bogotá", "Tunja", 150);
        Instant salida = Instant.parse("2026-11-20T10:00:00Z");
        Itinerary a = new Itinerary(UUID.randomUUID(), ruta, bus, salida, salida.plusSeconds(9000), BigDecimal.TEN);
        Itinerary b = new Itinerary(UUID.randomUUID(), ruta, bus, salida.plusSeconds(86400), salida.plusSeconds(95400),
                BigDecimal.TEN);

        publisher.publishAfterCommit(List.of(a, b));

        verify(seatMaps, times(1)).findByBusId(bus.getId());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publishAfterCommit(eq(CatalogEvents.ITINERARY_PUBLISHED), payload.capture());
        Map<String, Object> primero = (Map<String, Object>) payload.getAllValues().get(0);
        assertThat(primero).containsEntry("capacidad", 2).containsEntry("seatNumbers", List.of("1", "2"))
                .containsEntry("seats", List.of(Map.of("numero", "1", "categoria", "PREMIUM"),
                        Map.of("numero", "2", "categoria", "ESTANDAR")));
    }

    @Test
    void unMapaCorruptoEsError() {
        Bus bus = bus(BusType.UN_PISO);
        when(seatMaps.findByBusId(bus.getId())).thenReturn(Optional.of(new SeatMap(UUID.randomUUID(), bus.getId(), "{x")));

        assertThatThrownBy(() -> publisher.asientos(bus)).isInstanceOf(IllegalStateException.class);
    }

    private static Bus bus(BusType tipo) {
        return new Bus(UUID.randomUUID(), UUID.randomUUID(), "ABC123", tipo, SeatLayoutFactory.capacidad(tipo));
    }
}
