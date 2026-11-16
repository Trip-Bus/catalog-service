package com.tripbus.catalog.service;

import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.FareSeason;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.PassengerTypeFare;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.domain.SegmentPrice;
import com.tripbus.catalog.domain.Stop;
import com.tripbus.catalog.domain.TipoPasajero;
import com.tripbus.catalog.repository.FareSeasonRepository;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.PassengerTypeFareRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.repository.SeatSurchargeRepository;
import com.tripbus.catalog.repository.SegmentPriceRepository;
import com.tripbus.catalog.repository.StopRepository;
import com.tripbus.catalog.repository.TramoParada;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El precio "desde" de la búsqueda es exactamente el total de GET /itineraries/{id}/price para un ADULTO sin asiento
 * (PricingService), con los mismos datos: recorrido completo y tramo, temporada de la ruta y de la empresa (solo en
 * sus fechas), descuento de ADULTO, oferta activa y precio exacto del tramo.
 */
class PrecioDesdeConsistenteTest {

    private static final Instant AHORA = Instant.parse("2026-11-09T12:00:00Z");

    private final ItineraryRepository itineraries = mock(ItineraryRepository.class);
    private final RouteRepository routes = mock(RouteRepository.class);
    private final StopRepository stops = mock(StopRepository.class);
    private final SegmentPriceRepository segmentPrices = mock(SegmentPriceRepository.class);
    private final FareSeasonRepository seasons = mock(FareSeasonRepository.class);
    private final PassengerTypeFareRepository passengerTypes = mock(PassengerTypeFareRepository.class);
    private final Clock clock = Clock.fixed(AHORA, ZoneOffset.UTC);
    private final TripFinder finder = new TripFinder(itineraries, routes, stops, segmentPrices,
            new TarifaAdulto(seasons, passengerTypes), clock, 30);
    private final PricingService pricing = new PricingService(itineraries, stops, segmentPrices, seasons,
            passengerTypes, mock(SeatSurchargeRepository.class), mock(ItineraryPublisher.class), clock, 30);

    private final List<FareSeason> temporadas = new ArrayList<>();
    private final List<PassengerTypeFare> tipos = new ArrayList<>();
    private final List<SegmentPrice> exactos = new ArrayList<>();
    private final List<Itinerary> viajes = new ArrayList<>();
    private Route ruta;

    @BeforeEach
    void setUp() {
        Company empresa = new Company(UUID.randomUUID(), "Expreso Andino", null);
        ruta = new Route(UUID.randomUUID(), empresa, "Bogotá", "Medellín", 540);
        List<Stop> paradas = List.of(parada(1, "Bogotá", 0), parada(2, "Honda", 210),
                parada(3, "Puerto Triunfo", 330), parada(4, "Medellín", 540));
        LocalDate hoy = LocalDate.ofInstant(AHORA, CatalogTime.ZONE);
        // Temporada de la ruta mañana (1,15) y de toda la empresa hoy y mañana (1,30): mañana gana la de la ruta.
        temporadas.add(new FareSeason(UUID.randomUUID(), empresa.getId(), ruta.getId(), "Puente", hoy.plusDays(1),
                hoy.plusDays(1), new BigDecimal("1.15"), AHORA));
        temporadas.add(new FareSeason(UUID.randomUUID(), empresa.getId(), null, "Alta", hoy, hoy.plusDays(1),
                new BigDecimal("1.30"), AHORA));
        tipos.add(new PassengerTypeFare(UUID.randomUUID(), empresa.getId(), TipoPasajero.ADULTO, new BigDecimal("5")));
        tipos.add(new PassengerTypeFare(UUID.randomUUID(), empresa.getId(), TipoPasajero.NINO, new BigDecimal("30")));
        exactos.add(new SegmentPrice(UUID.randomUUID(), ruta.getId(), 3, 4, new BigDecimal("41700")));

        viajes.add(viaje(AHORA.plus(2, ChronoUnit.HOURS), "95000"));
        viajes.add(viaje(AHORA.plus(1, ChronoUnit.DAYS), "85500"));
        viajes.add(viaje(AHORA.plus(3, ChronoUnit.DAYS), "95000"));
        Itinerary conOferta = viaje(AHORA.plus(1, ChronoUnit.DAYS).plus(4, ChronoUnit.HOURS), "95000");
        conOferta.marcarOferta(UUID.randomUUID(), new BigDecimal("61900"), AHORA.plus(5, ChronoUnit.DAYS));
        viajes.add(conOferta);

        when(itineraries.search(anyString(), anyString(), any(), any())).thenAnswer(a -> enRango(a.getArgument(2),
                a.getArgument(3)));
        when(itineraries.searchByRoutes(anyCollection(), any(), any())).thenAnswer(a -> enRango(a.getArgument(1),
                a.getArgument(2)));
        when(itineraries.findDetailed(any())).thenAnswer(a -> viajes.stream()
                .filter(i -> i.getId().equals(a.getArgument(0))).findFirst());
        when(stops.findByRouteIdOrderByOrdenAsc(ruta.getId())).thenReturn(paradas);
        when(stops.tramos(anyString(), anyString())).thenReturn(List.of(
                new TramoParada(ruta.getId(), 2, 4, 210, 540, 540), new TramoParada(ruta.getId(), 3, 4, 330, 540, 540)));
        when(segmentPrices.findByRouteIdIn(anyCollection())).thenReturn(exactos);
        when(segmentPrices.findByRouteIdAndDesdeOrdenAndHastaOrden(any(), anyInt(), anyInt())).thenAnswer(a ->
                exactos.stream().filter(p -> p.getDesdeOrden() == (int) a.getArgument(1)
                        && p.getHastaOrden() == (int) a.getArgument(2)).findFirst());
        when(seasons.vigentes(any(), any(), any())).thenAnswer(a -> temporadas.stream()
                .filter(s -> s.getRouteId() == null || s.getRouteId().equals(a.getArgument(1)))
                .filter(s -> vigente(s, a.getArgument(2), a.getArgument(2))).toList());
        when(seasons.solapan(anyCollection(), any(), any())).thenAnswer(a -> temporadas.stream()
                .filter(s -> ((Collection<?>) a.getArgument(0)).contains(s.getCompanyId()))
                .filter(s -> vigente(s, a.getArgument(1), a.getArgument(2))).toList());
        when(passengerTypes.findByCompanyId(any())).thenReturn(tipos);
        when(passengerTypes.findByCompanyIdInAndTipo(anyCollection(), any())).thenAnswer(a -> tipos.stream()
                .filter(t -> t.getTipo() == a.getArgument(1)).toList());
    }

    @Test
    void elPrecioDesdeDeCadaViajeYTramoEsElTotalDeUnAdultoEnElDesglose() {
        List<TripSearchResult> resultados = finder.buscar("bogota", "medellin", AHORA,
                AHORA.plus(5, ChronoUnit.DAYS), SearchMode.TODOS);

        // 4 viajes directos y 2 tramos por viaje (Honda y Puerto Triunfo → Medellín).
        assertThat(resultados).hasSize(12);
        assertThat(resultados).anyMatch(r -> r.ofertaActiva() != null && r.esTramo());
        for (TripSearchResult r : resultados) {
            BigDecimal total = pricing.cotizar(r.itineraryId(), r.fromOrden(), r.toOrden(), "ADULTO").total();
            assertThat(r.precioDesde()).as(r.itineraryId() + " " + r.fromOrden() + "→" + r.toOrden())
                    .isEqualByComparingTo(total);
        }
        // Un caso a mano: viaje de mañana (temporada de la ruta 1,15) a 85.500 → 98.325 → 98.300; −5 %: 93.385 → 93.400.
        TripSearchResult manana = resultados.stream()
                .filter(r -> !r.esTramo() && r.precioBase().compareTo(new BigDecimal("85500")) == 0)
                .findFirst().orElseThrow();
        assertThat(manana.precioDesde()).isEqualByComparingTo("93400");
    }

    private List<Itinerary> enRango(Instant desde, Instant hasta) {
        return viajes.stream().filter(i -> !i.getFechaSalida().isBefore(desde)
                && i.getFechaSalida().isBefore(hasta)).toList();
    }

    private static boolean vigente(FareSeason s, LocalDate desde, LocalDate hasta) {
        return !s.getDesde().isAfter(hasta) && !s.getHasta().isBefore(desde);
    }

    private Itinerary viaje(Instant salida, String precio) {
        Bus bus = new Bus(UUID.randomUUID(), ruta.getCompany().getId(), "ABC123", BusType.UN_PISO, 40);
        return new Itinerary(UUID.randomUUID(), ruta, bus, salida, salida.plus(9, ChronoUnit.HOURS),
                new BigDecimal(precio));
    }

    private Stop parada(int orden, String nombre, int minutos) {
        return new Stop(UUID.randomUUID(), ruta.getId(), orden, nombre, 4.6, -74.1, minutos);
    }
}
