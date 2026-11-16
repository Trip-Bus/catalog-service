package com.tripbus.catalog.service;

import com.tripbus.catalog.domain.FareSeason;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.PassengerTypeFare;
import com.tripbus.catalog.domain.TipoPasajero;
import com.tripbus.catalog.repository.FareSeasonRepository;
import com.tripbus.catalog.repository.PassengerTypeFareRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lo que el precio "desde" de la búsqueda necesita además del tramo: la temporada del día de salida y el descuento
 * de ADULTO de la empresa, con las mismas reglas que {@link PricingService} (la temporada de la ruta gana a la de
 * toda la empresa; entre iguales, la más reciente). Dos consultas para todos los viajes de una búsqueda.
 */
@Component
public class TarifaAdulto {

    private final FareSeasonRepository seasonRepository;
    private final PassengerTypeFareRepository passengerTypeRepository;

    public TarifaAdulto(FareSeasonRepository seasonRepository, PassengerTypeFareRepository passengerTypeRepository) {
        this.seasonRepository = seasonRepository;
        this.passengerTypeRepository = passengerTypeRepository;
    }

    /** Temporadas y descuentos de las empresas de estos viajes, en el rango de sus fechas de salida. */
    public Factores cargar(Collection<Itinerary> viajes) {
        if (viajes.isEmpty()) {
            return new Factores(List.of(), Map.of());
        }
        Set<UUID> empresas = viajes.stream().map(i -> i.getRoute().getCompany().getId()).collect(Collectors.toSet());
        LocalDate desde = viajes.stream().map(TarifaAdulto::fecha).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate hasta = viajes.stream().map(TarifaAdulto::fecha).max(Comparator.naturalOrder()).orElseThrow();
        Map<UUID, BigDecimal> descuentos = passengerTypeRepository
                .findByCompanyIdInAndTipo(empresas, TipoPasajero.ADULTO).stream()
                .collect(Collectors.toMap(PassengerTypeFare::getCompanyId, PassengerTypeFare::getDescuentoPorcentaje,
                        (a, b) -> a));
        return new Factores(seasonRepository.solapan(empresas, desde, hasta), descuentos);
    }

    /** La temporada se busca por el día de salida del bus (como en PricingService), no por el del tramo. */
    static LocalDate fecha(Itinerary i) {
        return LocalDate.ofInstant(i.getFechaSalida(), CatalogTime.ZONE);
    }

    /** Temporadas que se cruzan con la búsqueda y descuento de ADULTO por empresa. */
    public record Factores(List<FareSeason> temporadas, Map<UUID, BigDecimal> descuentos) {

        /** Multiplicador de la temporada vigente el día de salida del viaje (1 sin temporada). */
        public BigDecimal multiplicador(Itinerary i) {
            UUID companyId = i.getRoute().getCompany().getId();
            UUID routeId = i.getRoute().getId();
            LocalDate fecha = fecha(i);
            return temporadas.stream()
                    .filter(s -> s.getCompanyId().equals(companyId))
                    .filter(s -> s.getRouteId() == null || s.getRouteId().equals(routeId))
                    .filter(s -> !s.getDesde().isAfter(fecha) && !s.getHasta().isBefore(fecha))
                    .min(PricingService.PRIORIDAD)
                    .map(FareSeason::getMultiplicador)
                    .orElse(BigDecimal.ONE);
        }

        /** Descuento de ADULTO de la empresa (0 si no lo configuró). */
        public BigDecimal descuento(UUID companyId) {
            return descuentos.getOrDefault(companyId, BigDecimal.ZERO);
        }
    }
}
