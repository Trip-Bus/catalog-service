package com.tripbus.catalog.service;

import com.tripbus.catalog.client.SeatInventoryClient;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.web.dto.TripSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** US-008: asientos disponibles en la búsqueda según lo que responda Seat Inventory. */
class SearchServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-12T12:00:00Z");
    private static final int CAPACIDAD = 40;

    private final SeatInventoryClient seatInventory = mock(SeatInventoryClient.class);
    private final SearchCache cache = mock(SearchCache.class);
    private final TripSearchResult viaje = viaje();
    private SearchService service;

    @BeforeEach
    void setUp() {
        service = new SearchService(mock(ItineraryRepository.class), mock(RouteRepository.class), seatInventory,
                cache, Clock.fixed(AHORA, ZoneOffset.UTC));
        when(cache.get(anyString(), anyString(), any())).thenReturn(Optional.of(List.of(viaje)));
    }

    @Test
    void itinerarioConocidoPorSeatInventoryUsaSusAsientosDisponibles() {
        when(seatInventory.availability(anyCollection())).thenReturn(Optional.of(Map.of(viaje.itineraryId(), 12)));

        TripSearchResult r = buscar();

        assertThat(r.asientosDisponibles()).isEqualTo(12);
        assertThat(r.agotado()).isFalse();
        assertThat(r.disponibilidadConocida()).isTrue();
    }

    @Test
    void itinerarioSinAsientosLibresQuedaAgotado() {
        when(seatInventory.availability(anyCollection())).thenReturn(Optional.of(Map.of(viaje.itineraryId(), 0)));

        TripSearchResult r = buscar();

        assertThat(r.asientosDisponibles()).isZero();
        assertThat(r.agotado()).isTrue();
        assertThat(r.disponibilidadConocida()).isTrue();
    }

    @Test
    void itinerarioQueSeatInventoryNoConoceTieneTodosLosAsientosLibres() {
        when(seatInventory.availability(anyCollection())).thenReturn(Optional.of(Map.of(UUID.randomUUID(), 3)));

        TripSearchResult r = buscar();

        assertThat(r.asientosDisponibles()).isEqualTo(CAPACIDAD);
        assertThat(r.agotado()).isFalse();
        assertThat(r.disponibilidadConocida()).isTrue();
    }

    @Test
    void seatInventoryCaidoDejaLaDisponibilidadDesconocida() {
        when(seatInventory.availability(anyCollection())).thenReturn(Optional.empty());

        TripSearchResult r = buscar();

        assertThat(r.asientosDisponibles()).isNull();
        assertThat(r.agotado()).isNull();
        assertThat(r.disponibilidadConocida()).isFalse();
    }

    private TripSearchResult buscar() {
        List<TripSearchResult> results = service.search("Bogotá", "Medellín", LocalDate.of(2026, 10, 13));
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    private static TripSearchResult viaje() {
        Instant salida = AHORA.plus(1, ChronoUnit.DAYS);
        return new TripSearchResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Expreso Andino",
                "Bogotá", "Medellín", salida, salida.plus(9, ChronoUnit.HOURS), 540, new BigDecimal("95000"),
                "COP", "DOS_PISOS", CAPACIDAD, null, null, null, false);
    }
}
