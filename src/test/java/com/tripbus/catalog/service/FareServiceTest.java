package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.CategoriaAsiento;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.FareSeason;
import com.tripbus.catalog.domain.PassengerTypeFare;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.domain.SeatSurcharge;
import com.tripbus.catalog.domain.SegmentPrice;
import com.tripbus.catalog.domain.Stop;
import com.tripbus.catalog.domain.TipoPasajero;
import com.tripbus.catalog.repository.FareSeasonRepository;
import com.tripbus.catalog.repository.PassengerTypeFareRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.repository.SeatSurchargeRepository;
import com.tripbus.catalog.repository.SegmentPriceRepository;
import com.tripbus.catalog.repository.StopRepository;
import com.tripbus.catalog.web.dto.FareDtos.DiscountRequest;
import com.tripbus.catalog.web.dto.FareDtos.PassengerTypeResponse;
import com.tripbus.catalog.web.dto.FareDtos.SeasonRequest;
import com.tripbus.catalog.web.dto.FareDtos.SeasonResponse;
import com.tripbus.catalog.web.dto.FareDtos.SegmentPriceRequest;
import com.tripbus.catalog.web.dto.FareDtos.SegmentPriceResponse;
import com.tripbus.catalog.web.dto.FareDtos.SurchargeRequest;
import com.tripbus.catalog.web.dto.FareDtos.SurchargeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-138/US-141: tarifas de la empresa, con su alcance (OPERADOR de la empresa o ADMIN) y sus validaciones. */
class FareServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-09T12:00:00Z");
    private static final LocalDate DIC = LocalDate.of(2026, 12, 15);

    private final FareSeasonRepository seasons = mock(FareSeasonRepository.class);
    private final PassengerTypeFareRepository passengerTypes = mock(PassengerTypeFareRepository.class);
    private final SeatSurchargeRepository surcharges = mock(SeatSurchargeRepository.class);
    private final SegmentPriceRepository segmentPrices = mock(SegmentPriceRepository.class);
    private final RouteRepository routes = mock(RouteRepository.class);
    private final StopRepository stops = mock(StopRepository.class);
    private final CompanyService companies = mock(CompanyService.class);
    private final SearchCache cache = mock(SearchCache.class);
    private final FareService service = new FareService(seasons, passengerTypes, surcharges, segmentPrices, routes,
            stops, companies, cache, Clock.fixed(AHORA, ZoneOffset.UTC));

    private final UUID empresa = UUID.randomUUID();
    private Route ruta;

    @BeforeEach
    void setUp() {
        ruta = new Route(UUID.randomUUID(), new Company(empresa, "Expreso", "900"), "Bogotá", "Medellín", 540);
        when(routes.findWithCompany(ruta.getId())).thenReturn(Optional.of(ruta));
        when(seasons.save(any(FareSeason.class))).thenAnswer(i -> i.getArgument(0));
        when(segmentPrices.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        when(stops.findByRouteIdOrderByOrdenAsc(ruta.getId())).thenReturn(List.of(parada(1), parada(2), parada(3),
                parada(4)));
    }

    // ---------------------------------------------------------------- temporadas

    @Test
    void creaEditaListaYBorraTemporadas() {
        SeasonResponse creada = service.createSeason(new SeasonRequest(null, ruta.getId(), " Navidad ", DIC,
                DIC.plusDays(20), new BigDecimal("1.3")), empresa);

        assertThat(creada.nombre()).isEqualTo("Navidad");
        assertThat(creada.companyId()).isEqualTo(empresa);
        verify(companies).requireActive(empresa);

        FareSeason guardada = new FareSeason(creada.id(), empresa, null, "Navidad", DIC, DIC, BigDecimal.ONE, AHORA);
        when(seasons.findById(creada.id())).thenReturn(Optional.of(guardada));
        SeasonResponse editada = service.updateSeason(creada.id(), new SeasonRequest(null, null, "Fin de año", DIC,
                DIC, new BigDecimal("0.5")), empresa);
        assertThat(editada.multiplicador()).isEqualByComparingTo("0.5");
        assertThat(editada.routeId()).isNull();

        when(seasons.findByCompanyIdOrderByDesdeAscNombreAsc(empresa)).thenReturn(List.of(guardada));
        when(seasons.findAllByOrderByDesdeAscNombreAsc()).thenReturn(List.of(guardada, guardada));
        assertThat(service.seasons(null, empresa)).hasSize(1);
        assertThat(service.seasons(empresa, null)).hasSize(1);
        assertThat(service.seasons(null, null)).hasSize(2);

        service.deleteSeason(creada.id(), null);
        verify(seasons).delete(guardada);
    }

    @Test
    void temporadaDeOtraEmpresaEs404() {
        FareSeason ajena = new FareSeason(UUID.randomUUID(), UUID.randomUUID(), null, "X", DIC, DIC, BigDecimal.ONE, AHORA);
        when(seasons.findById(ajena.getId())).thenReturn(Optional.of(ajena));
        UUID id = ajena.getId();
        assertThatThrownBy(() -> service.deleteSeason(id, empresa))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SEASON_NOT_FOUND");
    }

    @Test
    void validaLaTemporada() {
        assertThat(erroresTemporada(new SeasonRequest(null, null, null, null, null, null)))
                .containsKeys("nombre", "hasta", "multiplicador");
        assertThat(erroresTemporada(new SeasonRequest(null, null, " ", DIC, null, new BigDecimal("0.4"))))
                .containsKeys("nombre", "hasta", "multiplicador");
        assertThat(erroresTemporada(new SeasonRequest(null, null, "x".repeat(81), null, DIC, new BigDecimal("3.1"))))
                .containsKeys("nombre", "hasta", "multiplicador");
        assertThat(erroresTemporada(new SeasonRequest(null, null, "Puente", DIC.plusDays(1), DIC, BigDecimal.ONE)))
                .containsOnlyKeys("hasta");
        // Ruta de otra empresa o inexistente.
        Route ajena = new Route(UUID.randomUUID(), new Company(UUID.randomUUID(), "Otra", "800"), "A", "B", 60);
        when(routes.findWithCompany(ajena.getId())).thenReturn(Optional.of(ajena));
        assertThat(erroresTemporada(new SeasonRequest(null, ajena.getId(), "Puente", DIC, DIC, BigDecimal.ONE)))
                .containsOnlyKeys("routeId");
        assertThat(erroresTemporada(new SeasonRequest(null, UUID.randomUUID(), "Puente", DIC, DIC, BigDecimal.ONE)))
                .containsOnlyKeys("routeId");
    }

    // ---------------------------------------------------------------- tipos de pasajero

    @Test
    void losTresTiposConSuDescuentoOCeroPorDefecto() {
        when(passengerTypes.findByCompanyId(empresa)).thenReturn(List.of(
                new PassengerTypeFare(UUID.randomUUID(), empresa, TipoPasajero.NINO, new BigDecimal("25"))));

        assertThat(service.passengerTypes(null, empresa)).extracting(PassengerTypeResponse::descuentoPorcentaje)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(BigDecimal.ZERO, new BigDecimal("25"), BigDecimal.ZERO);
    }

    @Test
    void fijaElDescuentoNuevoOExistente() {
        PassengerTypeResponse nuevo = service.putPassengerType(TipoPasajero.ADULTO_MAYOR,
                new DiscountRequest(null, new BigDecimal("30")), empresa);
        assertThat(nuevo.descuentoPorcentaje()).isEqualByComparingTo("30");

        PassengerTypeFare fila = new PassengerTypeFare(UUID.randomUUID(), empresa, TipoPasajero.NINO, BigDecimal.TEN);
        when(passengerTypes.findByCompanyIdAndTipo(empresa, TipoPasajero.NINO)).thenReturn(Optional.of(fila));
        service.putPassengerType(TipoPasajero.NINO, new DiscountRequest(empresa, new BigDecimal("100")), null);
        assertThat(fila.getDescuentoPorcentaje()).isEqualByComparingTo("100");
        // ADULTO con 0 % sí es válido.
        assertThat(service.putPassengerType(TipoPasajero.ADULTO, new DiscountRequest(null, BigDecimal.ZERO), empresa)
                .tipo()).isEqualTo("ADULTO");

        service.deletePassengerType(TipoPasajero.NINO, null, empresa);
        verify(passengerTypes).delete(fila);
        service.deletePassengerType(TipoPasajero.ADULTO_MAYOR, empresa, null);
    }

    @Test
    void validaElDescuento() {
        for (String invalido : new String[] {null, "-1", "100.01"}) {
            DiscountRequest r = new DiscountRequest(null, invalido == null ? null : new BigDecimal(invalido));
            assertThatThrownBy(() -> service.putPassengerType(TipoPasajero.NINO, r, empresa))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo(FareService.ERROR);
        }
        DiscountRequest adulto = new DiscountRequest(null, BigDecimal.ONE);
        assertThatThrownBy(() -> service.putPassengerType(TipoPasajero.ADULTO, adulto, empresa))
                .isInstanceOf(ApiException.class);
        // El ADMIN debe indicar la empresa.
        assertThatThrownBy(() -> service.passengerTypes(null, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("COMPANY_REQUIRED");
    }

    // ---------------------------------------------------------------- recargos

    @Test
    void recargosPorCategoria() {
        SeatSurcharge premium = new SeatSurcharge(UUID.randomUUID(), empresa, CategoriaAsiento.PREMIUM, new BigDecimal("8000"));
        when(surcharges.findByCompanyId(empresa)).thenReturn(List.of(premium));
        when(surcharges.findByCompanyIdAndCategoria(empresa, CategoriaAsiento.PREMIUM)).thenReturn(Optional.of(premium));

        assertThat(service.surcharges(empresa, null)).extracting(SurchargeResponse::categoria)
                .containsExactly("ESTANDAR", "PREFERENCIAL", "PREMIUM");
        assertThat(service.surcharges(null, empresa).get(2).recargo()).isEqualByComparingTo("8000");

        service.putSurcharge(CategoriaAsiento.PREMIUM, new SurchargeRequest(null, new BigDecimal("12000")), empresa);
        assertThat(premium.getRecargo()).isEqualByComparingTo("12000");
        assertThat(service.putSurcharge(CategoriaAsiento.PREFERENCIAL, new SurchargeRequest(null, BigDecimal.ZERO),
                empresa).recargo()).isZero();

        service.deleteSurcharge(CategoriaAsiento.PREMIUM, null, empresa);
        verify(surcharges).delete(premium);
        service.deleteSurcharge(CategoriaAsiento.ESTANDAR, null, empresa);

        for (BigDecimal invalido : new BigDecimal[] {null, new BigDecimal("-1")}) {
            SurchargeRequest r = new SurchargeRequest(null, invalido);
            assertThatThrownBy(() -> service.putSurcharge(CategoriaAsiento.PREMIUM, r, empresa))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo(FareService.ERROR);
        }
    }

    // ---------------------------------------------------------------- precios por tramo

    @Test
    void reemplazaLosPreciosPorTramoEInvalidaLaCache() {
        when(segmentPrices.findByRouteIdOrderByDesdeOrdenAscHastaOrdenAsc(ruta.getId())).thenReturn(List.of(
                new SegmentPrice(UUID.randomUUID(), ruta.getId(), 2, 4, new BigDecimal("50000"))));

        List<SegmentPriceResponse> guardados = service.replaceSegmentPrices(ruta.getId(), List.of(
                new SegmentPriceRequest(2, 4, new BigDecimal("50000")),
                new SegmentPriceRequest(1, 3, new BigDecimal("60000"))), empresa);

        assertThat(guardados).extracting(SegmentPriceResponse::desdeOrden).containsExactly(2, 1);
        verify(segmentPrices).deleteByRouteId(ruta.getId());
        verify(cache).evictAll();
        assertThat(service.segmentPrices(ruta.getId(), null)).singleElement()
                .satisfies(p -> assertThat(p.precio()).isEqualByComparingTo("50000"));
        assertThat(service.replaceSegmentPrices(ruta.getId(), null, null)).isEmpty();
    }

    @Test
    void validaCadaTramo() {
        List<SegmentPriceRequest> precios = new ArrayList<>();
        precios.add(null);
        precios.add(new SegmentPriceRequest(null, 2, BigDecimal.TEN));
        precios.add(new SegmentPriceRequest(1, null, BigDecimal.TEN));
        precios.add(new SegmentPriceRequest(0, 2, BigDecimal.TEN));
        precios.add(new SegmentPriceRequest(2, 5, BigDecimal.TEN));
        precios.add(new SegmentPriceRequest(3, 3, BigDecimal.TEN));
        precios.add(new SegmentPriceRequest(1, 4, BigDecimal.TEN));
        precios.add(new SegmentPriceRequest(2, 3, null));
        precios.add(new SegmentPriceRequest(2, 3, BigDecimal.ZERO));
        precios.add(new SegmentPriceRequest(1, 2, BigDecimal.TEN));
        UUID routeId = ruta.getId();

        assertThatThrownBy(() -> service.replaceSegmentPrices(routeId, precios, empresa))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails()).containsOnlyKeys(
                        "precios[0]", "precios[1]", "precios[2]", "precios[3]", "precios[4]", "precios[5]",
                        "precios[6]", "precios[7].precio", "precios[8]", "precios[8].precio"));
        verify(segmentPrices, never()).deleteByRouteId(any());
    }

    @Test
    void rutaDeOtraEmpresaEs404() {
        UUID routeId = ruta.getId();
        UUID otra = UUID.randomUUID();
        assertThatThrownBy(() -> service.segmentPrices(routeId, otra))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ROUTE_NOT_FOUND");
    }

    private Map<String, Object> erroresTemporada(SeasonRequest request) {
        try {
            service.createSeason(request, empresa);
            throw new AssertionError("Debía fallar");
        } catch (ApiException e) {
            assertThat(e.getCode()).isEqualTo(FareService.ERROR);
            return e.getDetails();
        }
    }

    private Stop parada(int orden) {
        return new Stop(UUID.randomUUID(), ruta.getId(), orden, "P" + orden, 4.6, -74.1, orden * 60);
    }
}
