package com.tripbus.catalog.service;

import com.tripbus.catalog.client.SeatInventoryClient;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.common.observability.CatalogMetrics;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.repository.StopRepository;
import com.tripbus.catalog.repository.TerminalDeSalida;
import com.tripbus.catalog.web.dto.CalendarDay;
import com.tripbus.catalog.web.dto.OfertaResumen;
import com.tripbus.catalog.web.dto.PublicTripSearchResult;
import com.tripbus.catalog.web.dto.RoundTripResult;
import com.tripbus.catalog.web.dto.SearchFacets;
import com.tripbus.catalog.web.dto.TripSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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

/**
 * US-136 calendario, facetas, US-137 ida y vuelta y la búsqueda con tramos (US-138) ordenada por lo que ve el
 * pasajero. El calendario y las facetas parten de los mismos viajes que /trips/search.
 */
class SearchCalendarTest {

    /** 7:00 de Colombia del 9 de noviembre. */
    private static final Instant AHORA = Instant.parse("2026-11-09T12:00:00Z");
    private static final LocalDate HOY = LocalDate.of(2026, 11, 9);
    private static final String SIN_FILTROS = "AUTO:-:-:-";
    private static final UUID ANDINO = UUID.randomUUID();
    private static final UUID CARIBE = UUID.randomUUID();

    private final TripFinder finder = mock(TripFinder.class);
    private final StopRepository stops = mock(StopRepository.class);
    private final SeatInventoryClient seatInventory = mock(SeatInventoryClient.class);
    private final SearchCache cache = mock(SearchCache.class);
    private final SearchService service = new SearchService(finder, mock(RouteRepository.class), stops,
            seatInventory, cache, Clock.fixed(AHORA, ZoneOffset.UTC), CatalogMetrics.enMemoria());

    @BeforeEach
    void setUp() {
        when(cache.getCalendar(anyString(), anyString(), any(), anyInt(), anyString())).thenReturn(Optional.empty());
        when(cache.get(anyString(), anyString(), any(), anyString())).thenReturn(Optional.empty());
        when(seatInventory.availability(anyCollection())).thenReturn(Optional.of(Map.of()));
    }

    @Test
    void precioMinimoDeLosQueTienenCupoViajesYCupoPorDia() {
        TripSearchResult barato = viaje(AHORA.plus(5, ChronoUnit.HOURS), "95000", "60000");
        TripSearchResult lleno = viaje(AHORA.plus(6, ChronoUnit.HOURS), "50000", null);
        TripSearchResult agotado = viaje(AHORA.plus(1, ChronoUnit.DAYS), "80000", null);
        when(finder.buscar(eq("bogota"), eq("medellin"), any(), any(), eq(SearchMode.AUTO)))
                .thenReturn(List.of(barato, lleno, agotado));
        when(seatInventory.availability(anyCollection())).thenReturn(Optional.of(Map.of(
                lleno.itineraryId(), 0, agotado.itineraryId(), 0)));

        List<CalendarDay> dias = service.calendar("Bogotá", "Medellín", HOY, 3);

        assertThat(dias).extracting(CalendarDay::fecha).containsExactly(HOY, HOY.plusDays(1), HOY.plusDays(2));
        // Hoy: el agotado de 50.000 no cuenta para el mínimo; la oferta de 60.000 sí (Seat Inventory no lo conoce).
        assertThat(dias.get(0).precioMinimo()).isEqualByComparingTo("60000");
        assertThat(dias.get(0).viajes()).isEqualTo(2);
        assertThat(dias.get(0).hayCupo()).isTrue();
        // Mañana todo está agotado: hay un viaje pero ningún precio "desde".
        assertThat(dias.get(1).viajes()).isEqualTo(1);
        assertThat(dias.get(1).precioMinimo()).isNull();
        assertThat(dias.get(1).hayCupo()).isFalse();
        assertThat(dias.get(2).precioMinimo()).isNull();
        assertThat(dias.get(2).viajes()).isZero();
        assertThat(dias.get(2).hayCupo()).isFalse();
        // Hoy ya empezó: se busca desde ahora.
        verify(finder).buscar("bogota", "medellin", AHORA, Instant.parse("2026-11-12T05:00:00Z"), SearchMode.AUTO);
        verify(cache).putCalendar("bogota", "medellin", HOY, 3, SIN_FILTROS, dias);
    }

    @Test
    void conSeatInventoryCaidoElCupoQuedaDesconocidoYNoSeGuarda() {
        when(finder.buscar(any(), any(), any(), any(), any())).thenReturn(List.of(viaje(AHORA.plus(1, ChronoUnit.DAYS),
                "95000", null)));
        when(seatInventory.availability(anyCollection())).thenReturn(Optional.empty());

        List<CalendarDay> dias = service.calendar("Bogotá", "Medellín", HOY.plusDays(1), 1);

        assertThat(dias).singleElement().satisfies(d -> {
            assertThat(d.viajes()).isEqualTo(1);
            assertThat(d.hayCupo()).isNull();
            assertThat(d.precioMinimo()).isEqualByComparingTo("95000");
        });
        // Mañana todavía no empieza: se busca desde su medianoche.
        verify(finder).buscar("bogota", "medellin", Instant.parse("2026-11-10T05:00:00Z"),
                Instant.parse("2026-11-11T05:00:00Z"), SearchMode.AUTO);
        verify(cache, never()).putCalendar(anyString(), anyString(), any(), anyInt(), anyString(), anyList());
    }

    @Test
    void elCalendarioCacheadoNoConsultaNada() {
        List<CalendarDay> cacheado = List.of(new CalendarDay(HOY, BigDecimal.TEN, 1, true));
        when(cache.getCalendar("bogota", "medellin", HOY, 1, SIN_FILTROS)).thenReturn(Optional.of(cacheado));

        assertThat(service.calendar("Bogotá", "Medellín", HOY, 1)).isSameAs(cacheado);
        verify(finder, never()).buscar(any(), any(), any(), any(), any());
    }

    @Test
    void laLlaveDelCalendarioLlevaElModoYLosFiltros() {
        when(finder.buscar(any(), any(), any(), any(), any())).thenReturn(List.of());

        service.calendar("Bogotá", "Medellín", HOY, 2,
                new SearchFilters(ANDINO, "NOCHE", null, null, "UN_PISO", "salida", "asc", 0, 50, true));

        verify(finder).buscar(eq("bogota"), eq("medellin"), any(), any(), eq(SearchMode.TODOS));
        verify(cache).putCalendar(eq("bogota"), eq("medellin"), eq(HOY), eq(2),
                eq("TODOS:" + ANDINO + ":NOCHE:UN_PISO"), anyList());
    }

    @Test
    void validaDiasCiudadesYFiltros() {
        for (int dias : new int[] {0, 15}) {
            assertThatThrownBy(() -> service.calendar("Bogotá", "Medellín", HOY, dias))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_SEARCH");
        }
        assertThatThrownBy(() -> service.calendar("Bogotá", "bogota", HOY, 7)).isInstanceOf(ApiException.class);
        SearchFilters franjaMala = new SearchFilters(null, "MADRUGADA", null, null, null, "salida", "asc", 0, 50, null);
        assertThatThrownBy(() -> service.calendar("Bogotá", "Medellín", HOY, 7, franjaMala))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.facets("Bogotá", "Medellín", HOY, franjaMala))
                .isInstanceOf(ApiException.class);
        SearchFilters ninguno = SearchFilters.ninguno();
        assertThatThrownBy(() -> service.facets("", "Medellín", HOY, ninguno)).isInstanceOf(ApiException.class);
    }

    /**
     * Para cada día y cada combinación de filtros, el calendario da el mismo total que /trips/search ese día y como
     * precioMinimo el menor precioDesde de sus viajes con cupo. Hay ofertas vencidas, viajes que ya salieron,
     * agotados, temporadas y tramos.
     */
    @Test
    void cadaDiaDelCalendarioCoincideConLaBusquedaDeEseDiaConLosMismosFiltros() {
        List<TripSearchResult> todos = new ArrayList<>();
        for (int d = 0; d < 4; d++) {
            for (int h : new int[] {-3, 2, 7, 11, 14}) {
                Instant salida = AHORA.plus(d, ChronoUnit.DAYS).plus(h, ChronoUnit.HOURS);
                int k = todos.size();
                String oferta = k % 4 == 0 ? "70000" : null;
                TripSearchResult r = viaje(salida, String.valueOf(90000 + k * 1000), oferta, k % 3 == 0 ? CARIBE : ANDINO,
                        k % 2 == 0 ? "UN_PISO" : "DOS_PISOS", k % 8 == 0 ? AHORA.minus(1, ChronoUnit.MINUTES) : null)
                        .conTarifa(k % 5 == 0 ? new BigDecimal("1.25") : null, null);
                todos.add(k % 6 == 1 ? r.conTramo(2, 3, salida.plus(1, ChronoUnit.HOURS),
                        salida.plus(4, ChronoUnit.HOURS), new BigDecimal("40000")) : r);
            }
        }
        Map<UUID, Integer> disponibles = new java.util.HashMap<>();
        for (int k = 3; k < todos.size(); k += 7) {
            disponibles.put(todos.get(k).itineraryId(), 0);
        }
        when(seatInventory.availability(anyCollection())).thenReturn(Optional.of(disponibles));
        when(finder.buscar(anyString(), anyString(), any(), any(), any())).thenAnswer(a -> todos.stream()
                .filter(r -> !r.salidaEfectiva().isBefore(a.getArgument(2))
                        && r.salidaEfectiva().isBefore(a.getArgument(3)))
                .sorted(Comparator.comparing(TripSearchResult::salidaEfectiva)).toList());

        List<SearchFilters> combinaciones = Arrays.asList(SearchFilters.ninguno(),
                new SearchFilters(ANDINO, null, null, null, null, "salida", "asc", 0, 50, null),
                new SearchFilters(null, "TARDE", null, null, "DOS_PISOS", "salida", "asc", 0, 50, true),
                new SearchFilters(CARIBE, "NOCHE", null, null, "UN_PISO", "salida", "asc", 0, 50, false));
        for (SearchFilters f : combinaciones) {
            List<CalendarDay> calendario = service.calendar("Bogotá", "Medellín", HOY, 4, f);
            for (CalendarDay dia : calendario) {
                List<TripSearchResult> busqueda = service.search("Bogotá", "Medellín", dia.fecha(),
                        new SearchFilters(f.companyId(), f.franja(), null, null, f.tipoBus(), "precio", "asc", 0, 100,
                                f.tramos())).content();
                assertThat(dia.viajes()).as(dia.fecha() + " " + f).isEqualTo(busqueda.size());
                BigDecimal minimo = busqueda.stream().filter(r -> !Boolean.TRUE.equals(r.agotado()))
                        .map(TripSearchResult::precioDesde).min(Comparator.naturalOrder()).orElse(null);
                if (minimo == null) {
                    assertThat(dia.precioMinimo()).as(dia.fecha() + " " + f).isNull();
                } else {
                    assertThat(dia.precioMinimo()).as(dia.fecha() + " " + f).isEqualByComparingTo(minimo);
                }
            }
        }
    }

    @Test
    void lasFacetasDanElRangoDePreciosLasEmpresasDeLaRutaYLosTiposDeBus() {
        TripSearchResult manana = viaje(AHORA.plus(2, ChronoUnit.HOURS), "95000", null, ANDINO, "DOS_PISOS", null);
        TripSearchResult tarde = viaje(AHORA.plus(7, ChronoUnit.HOURS), "80000", "60000", ANDINO, "UN_PISO", null)
                .conTarifa(new BigDecimal("1.20"), null);
        TripSearchResult vencida = viaje(AHORA.plus(8, ChronoUnit.HOURS), "90000", "50000", CARIBE, "UN_PISO",
                AHORA.minus(1, ChronoUnit.MINUTES));
        TripSearchResult yaSalio = viaje(AHORA.minus(1, ChronoUnit.HOURS), "10000", null, CARIBE, "UN_PISO", null);
        UUID cordillera = UUID.randomUUID();
        TripSearchResult otroDia = viaje(AHORA.plus(3, ChronoUnit.DAYS), "70000", null, cordillera, "UN_PISO", null);
        when(finder.buscar(eq("bogota"), eq("medellin"), eq(Instant.parse("2026-11-09T12:00:00Z")),
                eq(Instant.parse("2026-11-10T05:00:00Z")), eq(SearchMode.AUTO)))
                .thenReturn(List.of(yaSalio, manana, tarde, vencida));
        when(finder.buscar("bogota", "medellin", AHORA, AHORA.plus(14, ChronoUnit.DAYS), SearchMode.AUTO))
                .thenReturn(List.of(manana, tarde, vencida, otroDia));
        when(stops.terminalesDeSalida(eq("bogota"), anyCollection())).thenReturn(List.of(
                new TerminalDeSalida(ANDINO, "Terminal del Norte"), new TerminalDeSalida(ANDINO, "Terminal Salitre")));

        SearchFacets f = service.facets("Bogotá", "Medellín", HOY, SearchFilters.ninguno());

        // 95.000; la oferta de 60.000 con temporada 1,2 = 72.000; la oferta vencida vuelve a 90.000.
        assertThat(f.precioMin()).isEqualByComparingTo("72000");
        assertThat(f.precioMax()).isEqualByComparingTo("95000");
        assertThat(f.tiposBus()).containsExactly("DOS_PISOS", "UN_PISO");
        assertThat(f.empresas()).extracting(SearchFacets.Empresa::nombre)
                .containsExactly("Expreso Andino", "Flota Cordillera", "Transportes Caribe Sol");
        assertThat(f.empresas()).extracting(SearchFacets.Empresa::viajes).containsExactly(2, 0, 1);
        assertThat(f.empresas()).extracting(SearchFacets.Empresa::terminal)
                .containsExactly("Terminal del Norte", null, null);
        verify(seatInventory, never()).availability(anyCollection());

        // Con filtros: el precio sale de los que cumplen todos; las salidas por empresa no usan el filtro de empresa.
        SearchFacets tardeUnPiso = service.facets("Bogotá", "Medellín", HOY,
                new SearchFilters(ANDINO, "TARDE", null, null, "UN_PISO", "salida", "asc", 0, 50, null));
        assertThat(tardeUnPiso.precioMin()).isEqualByComparingTo("72000");
        assertThat(tardeUnPiso.precioMax()).isEqualByComparingTo("72000");
        assertThat(tardeUnPiso.tiposBus()).containsExactly("UN_PISO");
        assertThat(tardeUnPiso.empresas()).extracting(SearchFacets.Empresa::viajes).containsExactly(1, 0, 1);
    }

    @Test
    void sinViajesLasFacetasVanVaciasYNoBuscanTerminales() {
        when(finder.buscar(any(), any(), any(), any(), any())).thenReturn(List.of());

        SearchFacets f = service.facets("Bogotá", "Medellín", HOY.plusDays(1), SearchFilters.ninguno());

        assertThat(f.precioMin()).isNull();
        assertThat(f.precioMax()).isNull();
        assertThat(f.empresas()).isEmpty();
        assertThat(f.tiposBus()).isEmpty();
        verify(stops, never()).terminalesDeSalida(anyString(), anyCollection());
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
        assertThat(r.ida()).extracting(PublicTripSearchResult::precioDesde)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("95000"));
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

    @Test
    void elOrdenYLosFiltrosDePrecioUsanElPrecioDesdeConTemporada() {
        // Sin temporada 80.000 es más barato; con la temporada de 1,5 sube a 120.000.
        TripSearchResult conTemporada = viaje(AHORA.plus(3, ChronoUnit.HOURS), "80000", null)
                .conTarifa(new BigDecimal("1.5"), BigDecimal.ZERO);
        TripSearchResult normal = viaje(AHORA.plus(4, ChronoUnit.HOURS), "100000", null);
        when(finder.buscar(any(), any(), any(), any(), any())).thenReturn(List.of(conTemporada, normal));

        var orden = service.search("Bogotá", "Medellín", HOY,
                new SearchFilters(null, null, null, null, null, "precio", "asc", 0, 10, null));
        assertThat(orden.content()).extracting(TripSearchResult::itineraryId)
                .containsExactly(normal.itineraryId(), conTemporada.itineraryId());
        var hasta110 = service.search("Bogotá", "Medellín", HOY,
                new SearchFilters(null, null, null, new BigDecimal("110000"), null, "salida", "asc", 0, 10, null));
        assertThat(hasta110.content()).extracting(TripSearchResult::itineraryId).containsExactly(normal.itineraryId());
    }

    private static TripSearchResult viaje(Instant salida, String precio, String oferta) {
        return viaje(salida, precio, oferta, UUID.randomUUID(), "UN_PISO", null);
    }

    private static TripSearchResult viaje(Instant salida, String precio, String oferta, UUID companyId, String tipo,
                                          Instant finOferta) {
        OfertaResumen o = oferta == null ? null
                : new OfertaResumen(UUID.randomUUID(), new BigDecimal(oferta), finOferta);
        String empresa = "Flota Cordillera";
        if (companyId.equals(ANDINO)) {
            empresa = "Expreso Andino";
        } else if (companyId.equals(CARIBE)) {
            empresa = "Transportes Caribe Sol";
        }
        return new TripSearchResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), empresa, "Bogotá",
                "Medellín", salida, salida.plus(9, ChronoUnit.HOURS), 540, new BigDecimal(precio), "COP", tipo,
                40, null, null, o, false, companyId);
    }
}
