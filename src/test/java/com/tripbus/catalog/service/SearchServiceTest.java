package com.tripbus.catalog.service;

import com.tripbus.catalog.client.SeatInventoryClient;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.Route;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    @Test
    void origenYDestinoObligatoriosYDistintos() {
        assertThatThrownBy(() -> service.search(" ", "Medellín", LocalDate.of(2026, 10, 13)))
                .isInstanceOf(ApiException.class).hasMessageContaining("obligatorios");
        assertThatThrownBy(() -> service.search("Bogotá", "", LocalDate.of(2026, 10, 13)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.search("Bogotá", "BOGOTA", LocalDate.of(2026, 10, 13)))
                .isInstanceOf(ApiException.class).hasMessageContaining("diferentes");
    }

    @Test
    void sinCacheConsultaLaBaseYGuardaElResultado() {
        ItineraryRepository repo = mock(ItineraryRepository.class);
        SearchService conBase = new SearchService(repo, mock(RouteRepository.class), seatInventory, cache,
                Clock.fixed(AHORA, ZoneOffset.UTC));
        when(cache.get(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(seatInventory.availability(anyCollection())).thenReturn(Optional.of(Map.of()));
        Itinerary i = itinerario(AHORA.plus(3, ChronoUnit.HOURS));
        i.marcarOferta(UUID.randomUUID(), new BigDecimal("50000"), AHORA.plus(1, ChronoUnit.HOURS));
        when(repo.search(eq("bogota"), eq("medellin"), any(), any())).thenReturn(List.of(i));

        // Hoy: desde "ahora" (no desde la medianoche).
        List<TripSearchResult> hoy = conBase.search("Bogotá", "Medellín", LocalDate.of(2026, 10, 12));
        assertThat(hoy).hasSize(1);
        assertThat(hoy.get(0).ofertaActiva()).isNotNull();
        assertThat(hoy.get(0).asientosDisponibles()).isEqualTo(40);
        verify(repo).search(eq("bogota"), eq("medellin"), eq(AHORA), any());
        verify(cache).put(eq("bogota"), eq("medellin"), eq(LocalDate.of(2026, 10, 12)), any());

        // Un día que ya terminó no consulta la base.
        assertThat(conBase.search("Bogotá", "Medellín", LocalDate.of(2026, 10, 10))).isEmpty();
        verify(repo, times(1)).search(any(), any(), any(), any());
    }

    @Test
    void losViajesQueYaSalieronNoSeOfrecenAunqueVenganDeCache() {
        TripSearchResult salio = new TripSearchResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "E",
                "Bogotá", "Medellín", AHORA.minusSeconds(60), AHORA.plusSeconds(3600), 60, BigDecimal.TEN, "COP",
                "UN_PISO", CAPACIDAD, null, null, null, false);
        when(cache.get(anyString(), anyString(), any())).thenReturn(Optional.of(List.of(salio)));
        when(seatInventory.availability(anyCollection())).thenReturn(Optional.of(Map.of()));
        assertThat(service.search("Bogotá", "Medellín", LocalDate.of(2026, 10, 12))).isEmpty();
    }

    @Test
    void ciudadesSinRepetirYOrdenadas() {
        RouteRepository rutas = mock(RouteRepository.class);
        when(rutas.findOrigenes()).thenReturn(List.of("Tunja", "Bogotá"));
        when(rutas.findDestinos()).thenReturn(List.of("Bogotá", "Cali"));
        SearchService s = new SearchService(mock(ItineraryRepository.class), rutas, seatInventory, cache,
                Clock.fixed(AHORA, ZoneOffset.UTC));
        assertThat(s.cities()).containsExactly("Bogotá", "Cali", "Tunja");
    }

    private static Itinerary itinerario(Instant salida) {
        Company empresa = new Company(UUID.randomUUID(), "Expreso Andino", null);
        Route ruta = new Route(UUID.randomUUID(), empresa, "Bogotá", "Medellín", 540);
        Bus bus = new Bus(UUID.randomUUID(), empresa.getId(), "ABC123", BusType.UN_PISO, CAPACIDAD);
        return new Itinerary(UUID.randomUUID(), ruta, bus, salida, salida.plus(9, ChronoUnit.HOURS),
                new BigDecimal("95000"));
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
