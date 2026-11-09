package com.tripbus.catalog.service;

import com.tripbus.catalog.client.SeatInventoryClient;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.common.observability.CatalogMetrics;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.web.dto.CalendarDay;
import com.tripbus.catalog.web.dto.OfertaResumen;
import com.tripbus.catalog.web.dto.PublicTripSearchResult;
import com.tripbus.catalog.web.dto.RoundTripResult;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-136 calendario, US-137 ida y vuelta y la búsqueda con tramos (US-138) ordenada por lo que ve el pasajero. */
class SearchCalendarTest {

    /** 7:00 de Colombia del 9 de noviembre. */
    private static final Instant AHORA = Instant.parse("2026-11-09T12:00:00Z");
    private static final LocalDate HOY = LocalDate.of(2026, 11, 9);

    private final TripFinder finder = mock(TripFinder.class);
    private final SeatInventoryClient seatInventory = mock(SeatInventoryClient.class);
    private final SearchCache cache = mock(SearchCache.class);
    private final SearchService service = new SearchService(finder, mock(RouteRepository.class), seatInventory, cache,
            Clock.fixed(AHORA, ZoneOffset.UTC), CatalogMetrics.enMemoria());

    @BeforeEach
    void setUp() {
        when(cache.getCalendar(anyString(), anyString(), any(), anyInt())).thenReturn(Optional.empty());
        when(cache.get(anyString(), anyString(), any(), anyString())).thenReturn(Optional.empty());
        when(seatInventory.availability(anyCollection())).thenReturn(Optional.of(Map.of()));
    }

    @Test
    void precioMinimoViajesYCupoPorDia() {
        TripSearchResult barato = viaje(AHORA.plus(5, ChronoUnit.HOURS), "95000", "60000");
        TripSearchResult lleno = viaje(AHORA.plus(6, ChronoUnit.HOURS), "70000", null);
        TripSearchResult agotado = viaje(AHORA.plus(1, ChronoUnit.DAYS), "80000", null);
        when(finder.buscar(eq("bogota"), eq("medellin"), any(), any(), eq(SearchMode.AUTO)))
                .thenReturn(List.of(barato, lleno, agotado));
        when(seatInventory.occupancy(anyCollection())).thenReturn(Optional.of(Map.of(
                lleno.itineraryId(), ocupacion(0), agotado.itineraryId(), ocupacion(0))));

        List<CalendarDay> dias = service.calendar("Bogotá", "Medellín", HOY, 3);

        assertThat(dias).extracting(CalendarDay::fecha).containsExactly(HOY, HOY.plusDays(1), HOY.plusDays(2));
        // Hoy: la oferta de 60.000 es el mínimo y el viaje que Seat Inventory no conoce tiene cupo.
        assertThat(dias.get(0).precioMinimo()).isEqualByComparingTo("60000");
        assertThat(dias.get(0).viajes()).isEqualTo(2);
        assertThat(dias.get(0).hayCupo()).isTrue();
        assertThat(dias.get(1).hayCupo()).isFalse();
        assertThat(dias.get(2).precioMinimo()).isNull();
        assertThat(dias.get(2).viajes()).isZero();
        assertThat(dias.get(2).hayCupo()).isFalse();
        // Hoy ya empezó: se busca desde ahora.
        verify(finder).buscar("bogota", "medellin", AHORA, Instant.parse("2026-11-12T05:00:00Z"), SearchMode.AUTO);
        verify(cache).putCalendar("bogota", "medellin", HOY, 3, dias);
    }

    @Test
    void conSeatInventoryCaidoElCupoQuedaDesconocidoYNoSeGuarda() {
        when(finder.buscar(any(), any(), any(), any(), any())).thenReturn(List.of(viaje(AHORA.plus(1, ChronoUnit.DAYS),
                "95000", null)));
        when(seatInventory.occupancy(anyCollection())).thenReturn(Optional.empty());

        List<CalendarDay> dias = service.calendar("Bogotá", "Medellín", HOY.plusDays(1), 1);

        assertThat(dias).singleElement().satisfies(d -> {
            assertThat(d.viajes()).isEqualTo(1);
            assertThat(d.hayCupo()).isNull();
        });
        // Mañana todavía no empieza: se busca desde su medianoche.
        verify(finder).buscar("bogota", "medellin", Instant.parse("2026-11-10T05:00:00Z"),
                Instant.parse("2026-11-11T05:00:00Z"), SearchMode.AUTO);
        verify(cache, never()).putCalendar(anyString(), anyString(), any(), anyInt(), anyList());
    }

    @Test
    void elCalendarioCacheadoNoConsultaNada() {
        List<CalendarDay> cacheado = List.of(new CalendarDay(HOY, BigDecimal.TEN, 1, true));
        when(cache.getCalendar("bogota", "medellin", HOY, 1)).thenReturn(Optional.of(cacheado));

        assertThat(service.calendar("Bogotá", "Medellín", HOY, 1)).isSameAs(cacheado);
        verify(finder, never()).buscar(any(), any(), any(), any(), any());
    }

    @Test
    void validaDiasYCiudades() {
        for (int dias : new int[] {0, 15}) {
            assertThatThrownBy(() -> service.calendar("Bogotá", "Medellín", HOY, dias))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_SEARCH");
        }
        assertThatThrownBy(() -> service.calendar("Bogotá", "bogota", HOY, 7)).isInstanceOf(ApiException.class);
    }

    @Test
    void idaYVueltaBuscaEnLosDosSentidos() {
        TripSearchResult ida = viaje(AHORA.plus(5, ChronoUnit.HOURS), "95000", null);
        TripSearchResult vuelta = viaje(AHORA.plus(3, ChronoUnit.DAYS), "90000", null);
        when(finder.buscar(eq("bogota"), eq("medellin"), any(), any(), eq(SearchMode.TODOS))).thenReturn(List.of(ida));
        when(finder.buscar(eq("medellin"), eq("bogota"), any(), any(), eq(SearchMode.TODOS))).thenReturn(List.of(vuelta));
        SearchFilters filtros = new SearchFilters(null, null, null, null, null, "salida", "asc", 0, 50, true);

        RoundTripResult r = service.roundTrip("Bogotá", "Medellín", HOY, HOY.plusDays(3), filtros);

        assertThat(r.ida()).extracting(PublicTripSearchResult::itineraryId).containsExactly(ida.itineraryId());
        assertThat(r.vuelta()).extracting(PublicTripSearchResult::itineraryId).containsExactly(vuelta.itineraryId());
        LocalDate antes = HOY.minusDays(1);
        assertThatThrownBy(() -> service.roundTrip("Bogotá", "Medellín", HOY, antes, filtros))
                .isInstanceOf(ApiException.class).hasMessageContaining("regreso");
    }

    @Test
    void conTramosOrdenaYFiltraPorLoQueVeElPasajero() {
        TripSearchResult directo = viaje(AHORA.plus(10, ChronoUnit.HOURS), "95000", null);
        TripSearchResult base = viaje(AHORA.plus(2, ChronoUnit.HOURS), "95000", null);
        // El bus sale a las 9:00 pero el pasajero sube 6 horas después (tarde) y viaja 3 horas por 40.000.
        TripSearchResult tramo = base.conTramo(3, 5, base.fechaSalida().plus(6, ChronoUnit.HOURS),
                base.fechaSalida().plus(9, ChronoUnit.HOURS), new BigDecimal("40000"));
        when(finder.buscar(any(), any(), any(), any(), eq(SearchMode.TODOS))).thenReturn(List.of(directo, tramo));

        var porPrecio = service.search("Bogotá", "Medellín", HOY,
                new SearchFilters(null, null, null, new BigDecimal("50000"), null, "precio", "asc", 0, 10, true));
        assertThat(porPrecio.content()).extracting(TripSearchResult::itineraryId).containsExactly(tramo.itineraryId());
        var porDuracion = service.search("Bogotá", "Medellín", HOY,
                new SearchFilters(null, "TARDE", null, null, null, "duracion", "asc", 0, 10, true));
        assertThat(porDuracion.content()).extracting(TripSearchResult::itineraryId)
                .containsExactly(tramo.itineraryId(), directo.itineraryId());
        verify(cache, times(2)).put(eq("bogota"), eq("medellin"), eq(HOY), eq("TODOS"), anyList());
    }

    private static SeatInventoryClient.Occupancy ocupacion(int disponibles) {
        return new SeatInventoryClient.Occupancy(10, disponibles, 0, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static TripSearchResult viaje(Instant salida, String precio, String oferta) {
        OfertaResumen o = oferta == null ? null : new OfertaResumen(UUID.randomUUID(), new BigDecimal(oferta), null);
        return new TripSearchResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Expreso", "Bogotá",
                "Medellín", salida, salida.plus(9, ChronoUnit.HOURS), 540, new BigDecimal(precio), "COP", "UN_PISO",
                40, null, null, o, false, UUID.randomUUID());
    }
}
