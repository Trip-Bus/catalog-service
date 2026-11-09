package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.domain.CategoriaAsiento;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.FareSeason;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.PassengerTypeFare;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.domain.SeatSurcharge;
import com.tripbus.catalog.domain.SegmentPrice;
import com.tripbus.catalog.domain.Stop;
import com.tripbus.catalog.domain.TipoPasajero;
import com.tripbus.catalog.repository.FareSeasonRepository;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.PassengerTypeFareRepository;
import com.tripbus.catalog.repository.SeatSurchargeRepository;
import com.tripbus.catalog.repository.SegmentPriceRepository;
import com.tripbus.catalog.repository.StopRepository;
import com.tripbus.catalog.web.dto.PriceBreakdown;
import com.tripbus.catalog.web.dto.PricingRequest;
import com.tripbus.catalog.web.dto.PricingRequest.Pasajero;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** US-138/US-141: desglose por pasajero con tramo, temporada, tipo de pasajero y categoría de asiento. */
class PricingServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-09T12:00:00Z");

    private final ItineraryRepository itineraries = mock(ItineraryRepository.class);
    private final StopRepository stops = mock(StopRepository.class);
    private final SegmentPriceRepository segmentPrices = mock(SegmentPriceRepository.class);
    private final FareSeasonRepository seasons = mock(FareSeasonRepository.class);
    private final PassengerTypeFareRepository passengerTypes = mock(PassengerTypeFareRepository.class);
    private final SeatSurchargeRepository surcharges = mock(SeatSurchargeRepository.class);
    private final PricingService service = new PricingService(itineraries, stops, segmentPrices, seasons,
            passengerTypes, surcharges, Clock.fixed(AHORA, ZoneOffset.UTC), 30);

    private Company empresa;
    private Route ruta;
    private Itinerary viaje;

    @BeforeEach
    void setUp() {
        empresa = new Company(UUID.randomUUID(), "Expreso Andino", "900100001-1");
        ruta = new Route(UUID.randomUUID(), empresa, "Bogotá", "Medellín", 540);
        Bus bus = new Bus(UUID.randomUUID(), empresa.getId(), "ABC123", BusType.DOS_PISOS, 60);
        Instant salida = AHORA.plus(2, ChronoUnit.DAYS);
        viaje = new Itinerary(UUID.randomUUID(), ruta, bus, salida, salida.plus(9, ChronoUnit.HOURS),
                new BigDecimal("95000"));
        when(itineraries.findDetailed(viaje.getId())).thenReturn(Optional.of(viaje));
        when(stops.findByRouteIdOrderByOrdenAsc(ruta.getId())).thenReturn(List.of(
                parada(1, "Bogotá", 0), parada(2, "Honda", 210), parada(3, "Puerto Triunfo", 330),
                parada(4, "Medellín", 540)));
        when(segmentPrices.findByRouteIdAndDesdeOrdenAndHastaOrden(any(), anyInt(), anyInt()))
                .thenReturn(Optional.empty());
        when(seasons.vigentes(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void sinParametrosEsUnAdultoEnElRecorridoCompleto() {
        PriceBreakdown p = service.cotizar(viaje.getId(), null, null, null);

        assertThat(p.fromOrden()).isEqualTo(1);
        assertThat(p.toOrden()).isEqualTo(4);
        assertThat(p.origen()).isEqualTo("Bogotá");
        assertThat(p.destino()).isEqualTo("Medellín");
        assertThat(p.conOferta()).isFalse();
        assertThat(p.temporada()).isNull();
        assertThat(p.precioTramo()).isEqualByComparingTo("95000");
        assertThat(p.pasajeros()).singleElement().satisfies(l -> {
            assertThat(l.tipo()).isEqualTo("ADULTO");
            assertThat(l.seatCategoria()).isNull();
            assertThat(l.precio()).isEqualByComparingTo("95000");
        });
        assertThat(p.total()).isEqualByComparingTo("95000");
        assertThat(p.salida()).isEqualTo(viaje.getFechaSalida());
        assertThat(p.llegada()).isEqualTo(viaje.getFechaSalida().plus(540, ChronoUnit.MINUTES));
    }

    @Test
    void tramoConTemporadaYTiposDePasajero() {
        when(seasons.vigentes(empresa.getId(), ruta.getId(), LocalDate.ofInstant(viaje.getFechaSalida(),
                CatalogTime.ZONE))).thenReturn(List.of(temporada(null, "1.2", AHORA)));
        when(passengerTypes.findByCompanyId(empresa.getId())).thenReturn(List.of(
                new PassengerTypeFare(UUID.randomUUID(), empresa.getId(), TipoPasajero.NINO, new BigDecimal("20"))));

        PriceBreakdown p = service.cotizar(viaje.getId(), 2, 4, "adulto, NINO,ADULTO_MAYOR");

        // Ejemplo del README: 58.100 × 1,2 = 69.720 → 69.700; niño con 20 %: 55.760 → 55.800.
        assertThat(p.precioTramo()).isEqualByComparingTo("58100");
        assertThat(p.temporada().multiplicador()).isEqualByComparingTo("1.2");
        assertThat(p.pasajeros()).extracting(PriceBreakdown.PrecioPasajero::precio)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("69700"), new BigDecimal("55800"), new BigDecimal("69700"));
        assertThat(p.total()).isEqualByComparingTo("195200");
        assertThat(p.salida()).isEqualTo(viaje.getFechaSalida().plus(210, ChronoUnit.MINUTES));
    }

    @Test
    void ganaLaTemporadaDeLaRutaYLuegoLaMasReciente() {
        FareSeason empresaReciente = temporada(null, "2", AHORA);
        FareSeason rutaVieja = temporada(ruta.getId(), "1.5", AHORA.minus(10, ChronoUnit.DAYS));
        FareSeason rutaReciente = temporada(ruta.getId(), "1.1", AHORA.minus(1, ChronoUnit.DAYS));
        List<FareSeason> todas = new ArrayList<>(List.of(empresaReciente, rutaVieja, rutaReciente));
        Collections.shuffle(todas);
        when(seasons.vigentes(any(), any(), any())).thenReturn(todas);

        assertThat(service.temporada(empresa.getId(), ruta.getId(), LocalDate.now())).contains(rutaReciente);
        when(seasons.vigentes(any(), any(), any())).thenReturn(List.of(empresaReciente,
                temporada(null, "1.3", AHORA.minus(3, ChronoUnit.DAYS))));
        assertThat(service.temporada(empresa.getId(), ruta.getId(), LocalDate.now())).contains(empresaReciente);
    }

    @Test
    void elPrecioExactoDelTramoSeUsaSinOferta() {
        when(segmentPrices.findByRouteIdAndDesdeOrdenAndHastaOrden(ruta.getId(), 2, 4)).thenReturn(Optional.of(
                new SegmentPrice(UUID.randomUUID(), ruta.getId(), 2, 4, new BigDecimal("50000"))));

        assertThat(service.cotizar(viaje.getId(), 2, 4, "ADULTO").precioTramo()).isEqualByComparingTo("50000");
    }

    @Test
    void conOfertaElTramoSaleDelPrecioDeLaOferta() {
        viaje.marcarOferta(UUID.randomUUID(), new BigDecimal("54000"), AHORA.plus(1, ChronoUnit.DAYS));
        when(segmentPrices.findByRouteIdAndDesdeOrdenAndHastaOrden(ruta.getId(), 2, 4)).thenReturn(Optional.of(
                new SegmentPrice(UUID.randomUUID(), ruta.getId(), 2, 4, new BigDecimal("50000"))));

        PriceBreakdown p = service.cotizar(viaje.getId(), 2, 4, null);

        // 54.000 × 330 / 540 = 33.000.
        assertThat(p.conOferta()).isTrue();
        assertThat(p.precioBase()).isEqualByComparingTo("54000");
        assertThat(p.precioTramo()).isEqualByComparingTo("33000");
    }

    @Test
    void internoSumaElRecargoDeLaCategoriaDelAsiento() {
        when(surcharges.findByCompanyId(empresa.getId())).thenReturn(List.of(
                new SeatSurcharge(UUID.randomUUID(), empresa.getId(), CategoriaAsiento.PREMIUM, new BigDecimal("10000"))));

        PriceBreakdown p = service.cotizar(new PricingRequest(viaje.getId(), null, null, List.of(
                new Pasajero(TipoPasajero.ADULTO, CategoriaAsiento.PREMIUM),
                new Pasajero(TipoPasajero.ADULTO, CategoriaAsiento.PREFERENCIAL),
                new Pasajero(TipoPasajero.NINO, null))));

        assertThat(p.pasajeros()).extracting(PriceBreakdown.PrecioPasajero::recargoCategoria)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(p.pasajeros().get(1).seatCategoria()).isEqualTo("PREFERENCIAL");
        assertThat(p.total()).isEqualByComparingTo("295000");
    }

    @Test
    void validaElTramoYLosPasajeros() {
        UUID id = viaje.getId();
        for (Integer[] tramo : new Integer[][] {{0, 4}, {1, 5}, {3, 3}, {4, 2}}) {
            assertThatThrownBy(() -> service.cotizar(id, tramo[0], tramo[1], null))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo(PricingService.ERROR);
        }
        assertThatThrownBy(() -> service.cotizar(id, null, null, "BEBE"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(PricingService.ERROR);
        assertThatThrownBy(() -> service.cotizar(new PricingRequest(id, null, null, List.of())))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(PricingService.ERROR);
        List<Pasajero> sinTipo = new ArrayList<>();
        sinTipo.add(new Pasajero(null, null));
        assertThatThrownBy(() -> service.cotizar(new PricingRequest(id, null, null, sinTipo)))
                .isInstanceOf(ApiException.class);
        List<Pasajero> nulo = new ArrayList<>();
        nulo.add(null);
        assertThatThrownBy(() -> service.cotizar(new PricingRequest(id, null, null, nulo)))
                .isInstanceOf(ApiException.class);
        List<Pasajero> muchos = Collections.nCopies(PricingService.MAX_PASAJEROS + 1, new Pasajero(TipoPasajero.ADULTO, null));
        assertThatThrownBy(() -> service.cotizar(new PricingRequest(id, null, null, muchos)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.cotizar(new PricingRequest(null, null, null, List.of())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.cotizar(new PricingRequest(id, null, null, null)))
                .isInstanceOf(ApiException.class);
        assertThat(PricingService.tipos(" ")).containsExactly(TipoPasajero.ADULTO);
    }

    @Test
    void viajeInexistenteOCanceladoNoSeCotiza() {
        UUID otro = UUID.randomUUID();
        when(itineraries.findDetailed(otro)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cotizar(otro, null, null, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ITINERARY_NOT_FOUND");

        viaje.cancelar();
        List<Pasajero> uno = List.of(new Pasajero(TipoPasajero.ADULTO, null));
        assertThatThrownBy(() -> service.cotizar(new PricingRequest(viaje.getId(), null, null, uno)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ITINERARY_CANCELLED");
        assertThat(service.minPorcentaje()).isEqualTo(30);
    }

    private FareSeason temporada(UUID routeId, String multiplicador, Instant creada) {
        return new FareSeason(UUID.randomUUID(), empresa.getId(), routeId, "Temporada", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31), new BigDecimal(multiplicador), creada);
    }

    private Stop parada(int orden, String nombre, int minutos) {
        return new Stop(UUID.randomUUID(), ruta.getId(), orden, nombre, 4.6, -74.1, minutos);
    }
}
