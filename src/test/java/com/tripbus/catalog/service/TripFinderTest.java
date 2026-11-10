package com.tripbus.catalog.service;

import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.domain.SegmentPrice;
import com.tripbus.catalog.domain.ServicioBus;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.repository.SegmentPriceRepository;
import com.tripbus.catalog.repository.StopRepository;
import com.tripbus.catalog.repository.TramoParada;
import com.tripbus.catalog.web.dto.TripSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-138: viajes directos y tramos intermedios de otras rutas. */
class TripFinderTest {

    private static final Instant AHORA = Instant.parse("2026-11-09T12:00:00Z");
    private static final Instant DESDE = AHORA.plus(1, ChronoUnit.HOURS);
    private static final Instant HASTA = AHORA.plus(1, ChronoUnit.DAYS);

    private final ItineraryRepository itineraries = mock(ItineraryRepository.class);
    private final RouteRepository routes = mock(RouteRepository.class);
    private final StopRepository stops = mock(StopRepository.class);
    private final SegmentPriceRepository segmentPrices = mock(SegmentPriceRepository.class);
    private final TripFinder finder = new TripFinder(itineraries, routes, stops, segmentPrices,
            Clock.fixed(AHORA, ZoneOffset.UTC), 30);

    private Route ruta;
    private Itinerary directo;

    @BeforeEach
    void setUp() {
        Company empresa = new Company(UUID.randomUUID(), "Expreso Andino", null);
        ruta = new Route(UUID.randomUUID(), empresa, "Bogotá", "Medellín", 540);
        directo = viaje(ruta, AHORA.plus(3, ChronoUnit.HOURS));
        when(itineraries.search(anyString(), anyString(), any(), any())).thenReturn(List.of(directo));
    }

    @Test
    void directosConServiciosDelBus() {
        directo.getBus().cambiarServicios(EnumSet.of(ServicioBus.WIFI, ServicioBus.BANO), AHORA);
        when(routes.existsByOrigenNormAndDestinoNorm("bogota", "medellin")).thenReturn(true);

        List<TripSearchResult> r = finder.buscar("bogota", "medellin", DESDE, HASTA, SearchMode.AUTO);

        assertThat(r).singleElement().satisfies(t -> {
            assertThat(t.esTramo()).isFalse();
            assertThat(t.servicios()).containsExactly("WIFI", "BANO");
            assertThat(t.companyId()).isEqualTo(ruta.getCompany().getId());
        });
        verify(stops, never()).tramos(anyString(), anyString());
    }

    @Test
    void soloDirectosNoBuscaTramos() {
        assertThat(finder.buscar("bogota", "medellin", DESDE, HASTA, SearchMode.DIRECTOS)).hasSize(1);
        verify(routes, never()).existsByOrigenNormAndDestinoNorm(anyString(), anyString());
        verify(stops, never()).tramos(anyString(), anyString());
    }

    @Test
    void sinRutaDirectaTraeLosTramosConSuPrecioYHoras() {
        Route larga = new Route(UUID.randomUUID(), ruta.getCompany(), "Villavicencio", "Medellín", 720);
        Itinerary pasa = viaje(larga, AHORA.plus(2, ChronoUnit.HOURS));
        when(itineraries.search(anyString(), anyString(), any(), any())).thenReturn(List.of());
        when(stops.tramos("honda", "medellin")).thenReturn(List.of(
                new TramoParada(larga.getId(), 3, 5, 390, 720, 720),
                // El recorrido completo nunca se toma como tramo (ya es un directo).
                new TramoParada(ruta.getId(), 1, 4, 0, 540, 540)));
        when(itineraries.searchByRoutes(anyCollection(), any(), any())).thenReturn(List.of(pasa));

        List<TripSearchResult> r = finder.buscar("honda", "medellin", DESDE, HASTA, SearchMode.AUTO);

        assertThat(r).singleElement().satisfies(t -> {
            assertThat(t.esTramo()).isTrue();
            assertThat(t.fromOrden()).isEqualTo(3);
            assertThat(t.toOrden()).isEqualTo(5);
            assertThat(t.salida()).isEqualTo(pasa.getFechaSalida().plus(390, ChronoUnit.MINUTES));
            assertThat(t.llegada()).isEqualTo(pasa.getFechaSalida().plus(720, ChronoUnit.MINUTES));
            // 95.000 × 330 / 720 = 43.541,67 → 43.500.
            assertThat(t.precio()).isEqualByComparingTo("43500");
            assertThat(t.duracionEfectiva()).isEqualTo(330);
        });
        verify(itineraries).searchByRoutes(Set.of(larga.getId()), DESDE.minus(390, ChronoUnit.MINUTES), HASTA);
    }

    @Test
    void conTramosTraeDirectosYTramosOrdenadosPorSalidaDelPasajero() {
        Route larga = new Route(UUID.randomUUID(), ruta.getCompany(), "Villavicencio", "Medellín", 720);
        Itinerary temprano = viaje(larga, AHORA.minus(1, ChronoUnit.HOURS));
        Itinerary fuera = viaje(larga, HASTA);
        Itinerary antes = viaje(larga, AHORA.minus(5, ChronoUnit.HOURS));
        when(stops.tramos("bogota", "medellin")).thenReturn(List.of(new TramoParada(larga.getId(), 3, 6, 180, 720, 720)));
        when(itineraries.searchByRoutes(anyCollection(), any(), any())).thenReturn(List.of(antes, temprano, fuera));
        // Precio exacto del tramo: se usa porque el viaje no tiene oferta.
        when(segmentPrices.findByRouteIdIn(anyCollection())).thenReturn(List.of(
                new SegmentPrice(UUID.randomUUID(), larga.getId(), 3, 6, new BigDecimal("70000"))));

        List<TripSearchResult> r = finder.buscar("bogota", "medellin", DESDE, HASTA, SearchMode.TODOS);

        assertThat(r).extracting(TripSearchResult::itineraryId).containsExactly(temprano.getId(), directo.getId());
        assertThat(r.get(0).precio()).isEqualByComparingTo("70000");
        assertThat(r.get(0).salidaEfectiva()).isEqualTo(AHORA.plus(2, ChronoUnit.HOURS));
    }

    @Test
    void conOfertaElTramoSeCalculaSobreElPrecioDeLaOfertaEIgnoraElExacto() {
        Route larga = new Route(UUID.randomUUID(), ruta.getCompany(), "Villavicencio", "Medellín", 720);
        Itinerary conOferta = viaje(larga, AHORA.plus(2, ChronoUnit.HOURS));
        conOferta.marcarOferta(UUID.randomUUID(), new BigDecimal("72000"), null);
        when(stops.tramos("honda", "medellin")).thenReturn(List.of(new TramoParada(larga.getId(), 3, 5, 360, 720, 720)));
        when(itineraries.searchByRoutes(anyCollection(), any(), any())).thenReturn(List.of(conOferta));
        when(segmentPrices.findByRouteIdIn(anyCollection())).thenReturn(List.of(
                new SegmentPrice(UUID.randomUUID(), larga.getId(), 3, 5, new BigDecimal("80000"))));

        List<TripSearchResult> r = finder.buscar("honda", "medellin", DESDE, HASTA, SearchMode.TODOS);

        // 72.000 × 360 / 720 = 36.000.
        assertThat(r).filteredOn(TripSearchResult::esTramo).singleElement()
                .satisfies(t -> {
                    assertThat(t.precio()).isEqualByComparingTo("36000");
                    assertThat(t.ofertaActiva()).isNotNull();
                    assertThat(t.precioEfectivo()).isEqualByComparingTo("36000");
                });
    }

    @Test
    void sinTramosNoConsultaViajesDeOtrasRutas() {
        when(stops.tramos("bogota", "medellin")).thenReturn(List.of(new TramoParada(ruta.getId(), 1, 4, 0, 540, 540)));

        assertThat(finder.buscar("bogota", "medellin", DESDE, HASTA, SearchMode.TODOS)).hasSize(1);
        verify(itineraries, never()).searchByRoutes(anyCollection(), any(), any());
    }

    private static Itinerary viaje(Route ruta, Instant salida) {
        Bus bus = new Bus(UUID.randomUUID(), ruta.getCompany().getId(), "ABC123", BusType.UN_PISO, 40);
        return new Itinerary(UUID.randomUUID(), ruta, bus, salida, salida.plus(ruta.getDuracionMinutos(), ChronoUnit.MINUTES),
                new BigDecimal("95000"));
    }
}
