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

    @Test
    void aplicaFranjaNocturnaPrecioDeOfertaOrdenYPaginacion() {
        UUID company = UUID.randomUUID();
        TripSearchResult noche = new TripSearchResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Andina",
                "Bogotá", "Medellín", AHORA.plus(19, ChronoUnit.HOURS), AHORA.plus(27, ChronoUnit.HOURS), 480,
                new BigDecimal("90000"), "COP", "DOS_PISOS", CAPACIDAD, null, null,
                new com.tripbus.catalog.web.dto.OfertaResumen(UUID.randomUUID(), new BigDecimal("60000"), AHORA.plus(2, ChronoUnit.DAYS)), false, company);
        when(cache.get(anyString(), anyString(), any())).thenReturn(Optional.of(List.of(viaje, noche)));
        when(seatInventory.availability(anyCollection())).thenReturn(Optional.of(Map.of()));
        var first = service.search("Bogotá", "Medellín", LocalDate.of(2026, 10, 12), company, "NOCHE",
                new BigDecimal("55000"), new BigDecimal("65000"), "DOS_PISOS", "precio", "asc", 0, 1);
        assertThat(first.total()).isEqualTo(1);
        assertThat(first.content()).extracting(TripSearchResult::itineraryId).containsExactly(noche.itineraryId());
        TripSearchResult tarde = new TripSearchResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Andina",
                "Bogotá", "Medellín", AHORA.plus(6, ChronoUnit.HOURS), AHORA.plus(13, ChronoUnit.HOURS), 420,
                new BigDecimal("70000"), "COP", "UN_PISO", CAPACIDAD, null, null, null, false, company);
        when(cache.get(anyString(), anyString(), any())).thenReturn(Optional.of(List.of(viaje, noche, tarde)));
        var afternoon = service.search("Bogotá", "Medellín", LocalDate.of(2026, 10, 12), null, "TARDE", null,
                null, null, "duracion", "desc", 0, 10);
        assertThat(afternoon.content()).extracting(TripSearchResult::itineraryId).containsExactly(tarde.itineraryId());
        assertThat(service.search("Bogotá", "Medellín", LocalDate.of(2026, 10, 12), null, null, null, null,
                null, "salida", "desc", 0, 1).content()).hasSize(1);
    }

    @Test
    void rechazaParametrosDeBusquedaInvalidos() {
        assertThatThrownBy(() -> service.search("Bogotá", "Medellín", LocalDate.now(), null, "MADRUGADA", null,
                null, null, "salida", "asc", 0, 50)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.search("Bogotá", "Medellín", LocalDate.now(), null, null,
                new BigDecimal("20"), new BigDecimal("10"), null, "salida", "asc", 0, 50)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.search("Bogotá", "Medellín", LocalDate.now(), null, null, null,
                null, null, "salida", "asc", 0, 101)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.search("Bogotá", "Medellín", LocalDate.now(), null, null,
                new BigDecimal("-1"), null, null, "salida", "asc", 0, 50)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.search("Bogotá", "Medellín", LocalDate.now(), null, null, null,
                null, "INVALIDO", "salida", "asc", 0, 50)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.search("Bogotá", "Medellín", LocalDate.now(), null, null, null,
                null, null, "otro", "asc", 0, 50)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.search("Bogotá", "Medellín", LocalDate.now(), null, null, null,
                null, null, "salida", "otro", 0, 50)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.search("Bogotá", "Medellín", LocalDate.now(), null, null, null,
                null, null, "salida", "asc", -1, 50)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.search("Bogotá", "Medellín", LocalDate.now(), null, null, null,
                new BigDecimal("-1"), null, "salida", "asc", 0, 50)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.search("Bogotá", "Medellín", LocalDate.now(), null, null, null,
                null, null, "salida", "asc", 0, 0)).isInstanceOf(ApiException.class);
    }

    @Test
    void filtrosCubrenLimitesDiurnosPrecioSinLimiteYTipoBusSinCoincidencias() {
        UUID company = UUID.randomUUID();
        TripSearchResult fiveAm = searchTrip("UN_PISO", new BigDecimal("40000"), AHORA.plus(3, ChronoUnit.HOURS), 180, company);
        TripSearchResult noon = searchTrip("DOS_PISOS", new BigDecimal("60000"), AHORA.plus(5, ChronoUnit.HOURS), 360, company);
        TripSearchResult sixPm = searchTrip("UN_PISO", new BigDecimal("80000"), AHORA.plus(11, ChronoUnit.HOURS), 480, company);
        TripSearchResult sevenPm = searchTrip("UN_PISO", new BigDecimal("90000"), AHORA.plus(12, ChronoUnit.HOURS), 600, company);
        TripSearchResult twoAm = searchTrip("DOS_PISOS", new BigDecimal("50000"), AHORA.plus(19, ChronoUnit.HOURS), 540, company);
        when(cache.get(anyString(), anyString(), any())).thenReturn(Optional.of(List.of(fiveAm, noon, sixPm, sevenPm, twoAm)));
        when(seatInventory.availability(anyCollection())).thenReturn(Optional.of(Map.of()));
        var morning = service.search("Bogotá", "Medellín", LocalDate.of(2026, 10, 12), null, "MANANA", null, null,
                null, "salida", "asc", 0, 10);
        assertThat(morning.content()).extracting(TripSearchResult::itineraryId).containsExactly(fiveAm.itineraryId());
        var afternoon = service.search("Bogotá", "Medellín", LocalDate.of(2026, 10, 12), null, "TARDE", null, null,
                null, "salida", "asc", 0, 10);
        assertThat(afternoon.content()).extracting(TripSearchResult::itineraryId).containsExactly(noon.itineraryId());
        var evening = service.search("Bogotá", "Medellín", LocalDate.of(2026, 10, 12), null, "NOCHE", null, null,
                null, "salida", "asc", 0, 10);
        assertThat(evening.content()).extracting(TripSearchResult::itineraryId)
                .containsExactly(sixPm.itineraryId(), sevenPm.itineraryId(), twoAm.itineraryId());
        assertThat(service.search("Bogotá", "Medellín", LocalDate.of(2026, 10, 12), null, null,
                new BigDecimal("60000"), null, "UN_PISO", "precio", "desc", 0, 10).content())
                .extracting(TripSearchResult::itineraryId).containsExactly(sevenPm.itineraryId(), sixPm.itineraryId());
        assertThat(service.search("Bogotá", "Medellín", LocalDate.of(2026, 10, 12), null, null, null,
                new BigDecimal("45000"), "UN_PISO", "precio", "asc", 0, 10).content())
                .extracting(TripSearchResult::itineraryId).containsExactly(fiveAm.itineraryId());
        assertThat(service.search("Bogotá", "Medellín", LocalDate.of(2026, 10, 12), null, null, null,
                new BigDecimal("49000"), "DOS_PISOS", "salida", "asc", 0, 10).content()).isEmpty();
        assertThat(service.search("Bogotá", "Medellín", LocalDate.of(2026, 10, 12), null, null, null,
                null, null, "salida", "asc", 0, 10).content()).hasSize(5);
    }

    private static TripSearchResult searchTrip(String type, BigDecimal price, Instant departure, int duration, UUID company) {
        return new TripSearchResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Empresa", "Bogotá", "Medellín",
                departure, departure.plusSeconds(duration * 60L), duration, price, "COP", type, CAPACIDAD,
                null, null, null, false, company);
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
