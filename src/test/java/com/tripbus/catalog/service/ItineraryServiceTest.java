package com.tripbus.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.common.events.EventPublisher;
import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.domain.SeatMap;
import com.tripbus.catalog.domain.Stop;
import com.tripbus.catalog.events.CatalogEvents;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.repository.SeatMapRepository;
import com.tripbus.catalog.repository.StopRepository;
import com.tripbus.catalog.web.dto.ItineraryDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Consulta pública de un viaje (detalle, mapa, precio, paradas) y cancelación. */
class ItineraryServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-21T15:00:00Z");

    private final ItineraryRepository itineraries = mock(ItineraryRepository.class);
    private final RouteRepository routes = mock(RouteRepository.class);
    private final StopRepository stops = mock(StopRepository.class);
    private final SeatMapRepository seatMaps = mock(SeatMapRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final SearchCache cache = mock(SearchCache.class);
    private final ItineraryService service = new ItineraryService(itineraries, routes, stops, seatMaps, publisher,
            cache, new ObjectMapper(), Clock.fixed(AHORA, ZoneOffset.UTC));

    private Itinerary viaje;

    @BeforeEach
    void setUp() {
        Company empresa = new Company(UUID.randomUUID(), "Expreso", "900100001");
        Route ruta = new Route(UUID.randomUUID(), empresa, "Bogotá", "Tunja", 150);
        Bus bus = new Bus(UUID.randomUUID(), empresa.getId(), "ABC123", BusType.UN_PISO, 3);
        Instant salida = AHORA.plus(1, ChronoUnit.DAYS);
        viaje = new Itinerary(UUID.randomUUID(), ruta, bus, salida, salida.plus(150, ChronoUnit.MINUTES),
                new BigDecimal("28000"));
        when(itineraries.findDetailed(viaje.getId())).thenReturn(Optional.of(viaje));
        when(stops.findByRouteIdOrderByOrdenAsc(ruta.getId())).thenReturn(List.of(
                new Stop(UUID.randomUUID(), ruta.getId(), 1, "Bogotá", 4.6, -74.1, 0),
                new Stop(UUID.randomUUID(), ruta.getId(), 2, "Tunja", 5.5, -73.3, 150)));
    }

    @Test
    void detalleConHoraProgramadaDeCadaParadaYOfertaActiva() {
        viaje.marcarOferta(UUID.randomUUID(), new BigDecimal("20000"), AHORA.plus(2, ChronoUnit.HOURS));
        ItineraryDetail d = service.detail(viaje.getId());
        assertThat(d.paradas()).hasSize(2);
        assertThat(d.paradas().get(1).horaProgramada()).isEqualTo(viaje.getFechaLlegadaEstimada());
        assertThat(d.ofertaActiva()).isNotNull();
        assertThat(d.empresa()).isEqualTo("Expreso");

        viaje.desmarcarOferta();
        assertThat(service.price(viaje.getId()).ofertaActiva()).isNull();
    }

    @Test
    void viajeInexistenteEs404() {
        UUID otro = UUID.randomUUID();
        when(itineraries.findDetailed(otro)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(otro))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("ITINERARY_NOT_FOUND");
    }

    @Test
    void mapaDeAsientosDelBus() {
        when(seatMaps.findByBusId(viaje.getBus().getId())).thenReturn(Optional.of(new SeatMap(UUID.randomUUID(),
                viaje.getBus().getId(), "{\"pisos\":[{\"celdas\":[{\"tipo\":\"ASIENTO\",\"asiento\":\"1\"},"
                + "{\"tipo\":\"PASILLO\"},{\"tipo\":\"ASIENTO\",\"asiento\":\"2\"}]}]}")));
        assertThat(service.seatMap(viaje.getId()).asientos()).containsExactly("1", "2");
    }

    @Test
    void sinMapaOConMapaCorrupto() {
        when(seatMaps.findByBusId(viaje.getBus().getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.seatMap(viaje.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("SEAT_MAP_NOT_FOUND");

        when(seatMaps.findByBusId(viaje.getBus().getId()))
                .thenReturn(Optional.of(new SeatMap(UUID.randomUUID(), viaje.getBus().getId(), "{no es json")));
        assertThatThrownBy(() -> service.seatMap(viaje.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void paradasDeUnaRuta() {
        UUID ruta = viaje.getRoute().getId();
        when(routes.existsById(ruta)).thenReturn(true);
        assertThat(service.stops(ruta)).hasSize(2).allSatisfy(s -> assertThat(s.horaProgramada()).isNull());

        UUID otra = UUID.randomUUID();
        assertThatThrownBy(() -> service.stops(otra))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("ROUTE_NOT_FOUND");
    }

    @Test
    void cancelarPublicaElEventoYNoSePuedeCancelarDosVeces() {
        assertThat(service.cancel(viaje.getId(), "Daño mecánico").estado()).isEqualTo("CANCELADO");
        verify(publisher).publishAfterCommit(eq(CatalogEvents.ITINERARY_CANCELLED), any());
        verify(cache).evictAll();

        assertThatThrownBy(() -> service.cancel(viaje.getId(), "otra vez"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("ITINERARY_ALREADY_CANCELLED");
    }

    @Test
    void ofertaActivaSegunSuFin() {
        assertThat(viaje.tieneOfertaActiva(AHORA)).isFalse();
        viaje.marcarOferta(UUID.randomUUID(), new BigDecimal("1"), null);
        assertThat(viaje.tieneOfertaActiva(AHORA)).isTrue();
        viaje.marcarOferta(UUID.randomUUID(), new BigDecimal("1"), AHORA.minusSeconds(1));
        assertThat(viaje.tieneOfertaActiva(AHORA)).isFalse();
        viaje.marcarOferta(UUID.randomUUID(), new BigDecimal("1"), AHORA.plusSeconds(1));
        assertThat(viaje.tieneOfertaActiva(AHORA)).isTrue();
    }
}
