package com.tripbus.catalog.service;

import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.FareSeason;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.repository.FareSeasonRepository;
import com.tripbus.catalog.repository.PassengerTypeFareRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** La temporada y el descuento de ADULTO del precio "desde", con las mismas reglas que PricingService. */
class TarifaAdultoTest {

    /** 7:00 de Colombia del 9 de noviembre. */
    private static final Instant SALIDA = Instant.parse("2026-11-09T12:00:00Z");
    private static final LocalDate DIA = LocalDate.of(2026, 11, 9);

    private final FareSeasonRepository seasons = mock(FareSeasonRepository.class);
    private final PassengerTypeFareRepository passengerTypes = mock(PassengerTypeFareRepository.class);
    private final TarifaAdulto tarifa = new TarifaAdulto(seasons, passengerTypes);

    private final Company empresa = new Company(UUID.randomUUID(), "Expreso Andino", null);
    private final Route ruta = new Route(UUID.randomUUID(), empresa, "Bogotá", "Medellín", 540);
    private final Route otraRuta = new Route(UUID.randomUUID(), empresa, "Bogotá", "Tunja", 150);

    @Test
    void laTemporadaDeLaRutaGanaALaDeLaEmpresaYSoloAplicaEnSusFechas() {
        Itinerary hoy = viaje(ruta, SALIDA);
        Itinerary otraRutaHoy = viaje(otraRuta, SALIDA);
        Itinerary pasado = viaje(ruta, SALIDA.plus(2, ChronoUnit.DAYS));
        Company otra = new Company(UUID.randomUUID(), "Flota Cordillera", null);
        Itinerary deOtraEmpresa = viaje(new Route(UUID.randomUUID(), otra, "Bogotá", "Cali", 600), SALIDA);
        when(seasons.solapan(Set.of(empresa.getId(), otra.getId()), DIA, DIA.plusDays(2))).thenReturn(List.of(
                temporada(null, DIA, DIA.plusDays(1), "1.10", SALIDA.minus(1, ChronoUnit.DAYS)),
                temporada(ruta.getId(), DIA, DIA, "1.30", SALIDA.minus(10, ChronoUnit.DAYS))));

        TarifaAdulto.Factores f = tarifa.cargar(List.of(hoy, otraRutaHoy, pasado, deOtraEmpresa));

        assertThat(f.multiplicador(hoy)).isEqualByComparingTo("1.30");
        assertThat(f.multiplicador(otraRutaHoy)).isEqualByComparingTo("1.10");
        assertThat(f.multiplicador(pasado)).isEqualByComparingTo("1");
        assertThat(f.multiplicador(deOtraEmpresa)).isEqualByComparingTo("1");
        assertThat(f.descuento(empresa.getId())).isEqualByComparingTo("0");
    }

    @Test
    void sinViajesNoConsulta() {
        TarifaAdulto.Factores f = tarifa.cargar(List.of());

        assertThat(f.temporadas()).isEmpty();
        assertThat(f.descuentos()).isEmpty();
        verify(seasons, never()).solapan(anyCollection(), any(), any());
    }

    private FareSeason temporada(UUID routeId, LocalDate desde, LocalDate hasta, String multiplicador, Instant creada) {
        return new FareSeason(UUID.randomUUID(), empresa.getId(), routeId, "Temporada", desde, hasta,
                new BigDecimal(multiplicador), creada);
    }

    private static Itinerary viaje(Route ruta, Instant salida) {
        Bus bus = new Bus(UUID.randomUUID(), ruta.getCompany().getId(), "ABC123", BusType.UN_PISO, 40);
        return new Itinerary(UUID.randomUUID(), ruta, bus, salida, salida.plus(ruta.getDuracionMinutos(),
                ChronoUnit.MINUTES), new BigDecimal("95000"));
    }
}
