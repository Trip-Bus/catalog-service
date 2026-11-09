package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.CategoriaAsiento;
import com.tripbus.catalog.domain.FareSeason;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.ItineraryStatus;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * US-138/US-141: precio de un tramo de un viaje por pasajero, con temporada, tipo de pasajero y recargo de la
 * categoría del asiento (reglas en {@link PricingRules}). Lo usan GET /itineraries/{id}/price y POST
 * /internal/pricing (booking cobra con él).
 */
@Service
public class PricingService {

    public static final String ERROR = "INVALID_PRICE_REQUEST";
    static final int MAX_PASAJEROS = 20;

    /** Si se cruzan temporadas gana la de la ruta y, entre iguales, la más reciente. */
    static final Comparator<FareSeason> PRIORIDAD = Comparator
            .comparing((FareSeason s) -> s.getRouteId() == null)
            .thenComparing(FareSeason::getCreadoEn, Comparator.reverseOrder())
            .thenComparing(FareSeason::getId);

    private final ItineraryRepository itineraryRepository;
    private final StopRepository stopRepository;
    private final SegmentPriceRepository segmentPriceRepository;
    private final FareSeasonRepository seasonRepository;
    private final PassengerTypeFareRepository passengerTypeRepository;
    private final SeatSurchargeRepository surchargeRepository;
    private final Clock clock;
    private final int minPorcentaje;

    public PricingService(ItineraryRepository itineraryRepository, StopRepository stopRepository,
                          SegmentPriceRepository segmentPriceRepository, FareSeasonRepository seasonRepository,
                          PassengerTypeFareRepository passengerTypeRepository,
                          SeatSurchargeRepository surchargeRepository, Clock clock,
                          @Value("${tripbus.catalog.min-segment-percent:30}") int minPorcentaje) {
        this.itineraryRepository = itineraryRepository;
        this.stopRepository = stopRepository;
        this.segmentPriceRepository = segmentPriceRepository;
        this.seasonRepository = seasonRepository;
        this.passengerTypeRepository = passengerTypeRepository;
        this.surchargeRepository = surchargeRepository;
        this.clock = clock;
        this.minPorcentaje = minPorcentaje;
    }

    public int minPorcentaje() {
        return minPorcentaje;
    }

    /** POST /internal/pricing: un viaje cancelado no se cobra (409 ITINERARY_CANCELLED). */
    @Transactional(readOnly = true)
    public PriceBreakdown cotizar(PricingRequest request) {
        if (request.itineraryId() == null || request.pasajeros() == null) {
            throw ApiException.badRequest(ERROR, "itineraryId y pasajeros son obligatorios");
        }
        Itinerary i = buscar(request.itineraryId());
        if (i.getEstado() == ItineraryStatus.CANCELADO) {
            throw ApiException.conflict("ITINERARY_CANCELLED", "El viaje está cancelado");
        }
        return cotizar(i, request.fromOrden(), request.toOrden(), request.pasajeros());
    }

    /** GET /itineraries/{id}/price?from=&to=&tipos=: pasajeros sin categoría de asiento (sin recargo). */
    @Transactional(readOnly = true)
    public PriceBreakdown cotizar(UUID itineraryId, Integer fromOrden, Integer toOrden, String tipos) {
        List<PricingRequest.Pasajero> pasajeros = tipos(tipos).stream()
                .map(t -> new PricingRequest.Pasajero(t, null)).toList();
        return cotizar(buscar(itineraryId), fromOrden, toOrden, pasajeros);
    }

    /** "ADULTO,NINO" → [ADULTO, NINO]; vacío o null → [ADULTO]. */
    static List<TipoPasajero> tipos(String tipos) {
        if (tipos == null || tipos.isBlank()) {
            return List.of(TipoPasajero.ADULTO);
        }
        try {
            return Arrays.stream(tipos.split(",")).map(t -> TipoPasajero.valueOf(t.trim().toUpperCase(Locale.ROOT)))
                    .toList();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(ERROR, "Tipos de pasajero válidos: ADULTO, NINO, ADULTO_MAYOR");
        }
    }

    PriceBreakdown cotizar(Itinerary i, Integer fromOrden, Integer toOrden, List<PricingRequest.Pasajero> pasajeros) {
        if (pasajeros.isEmpty() || pasajeros.size() > MAX_PASAJEROS
                || pasajeros.stream().anyMatch(p -> p == null || p.tipo() == null)) {
            throw ApiException.badRequest(ERROR, "Entre 1 y " + MAX_PASAJEROS + " pasajeros, cada uno con su tipo");
        }
        UUID routeId = i.getRoute().getId();
        List<Stop> paradas = stopRepository.findByRouteIdOrderByOrdenAsc(routeId);
        int desde = fromOrden == null ? 1 : fromOrden;
        int hasta = toOrden == null ? paradas.size() : toOrden;
        if (desde < 1 || hasta > paradas.size() || desde >= hasta) {
            throw ApiException.badRequest(ERROR, "El tramo debe ir de una parada a otra posterior de la ruta (1 a "
                    + paradas.size() + ")");
        }
        Stop origen = paradas.get(desde - 1);
        Stop destino = paradas.get(hasta - 1);
        boolean conOferta = i.tieneOfertaActiva(clock.instant());
        BigDecimal base = conOferta ? i.getPrecioOferta() : i.getPrecioBase();
        BigDecimal exacto = conOferta ? null : segmentPriceRepository
                .findByRouteIdAndDesdeOrdenAndHastaOrden(routeId, desde, hasta).map(SegmentPrice::getPrecio).orElse(null);
        BigDecimal tramo = PricingRules.tramo(base, origen.getMinutosDesdeSalida(), destino.getMinutosDesdeSalida(),
                i.getRoute().getDuracionMinutos(), exacto, minPorcentaje);

        UUID companyId = i.getRoute().getCompany().getId();
        Optional<FareSeason> temporada = temporada(companyId, routeId,
                LocalDate.ofInstant(i.getFechaSalida(), CatalogTime.ZONE));
        BigDecimal conTemporada = PricingRules.conTemporada(tramo,
                temporada.map(FareSeason::getMultiplicador).orElse(BigDecimal.ONE));
        Map<TipoPasajero, BigDecimal> descuentos = descuentos(companyId);
        Map<CategoriaAsiento, BigDecimal> recargos = recargos(companyId);
        List<PriceBreakdown.PrecioPasajero> lineas = pasajeros.stream().map(p -> {
            BigDecimal descuento = descuentos.getOrDefault(p.tipo(), BigDecimal.ZERO);
            BigDecimal recargo = p.seatCategoria() == null ? BigDecimal.ZERO
                    : recargos.getOrDefault(p.seatCategoria(), BigDecimal.ZERO);
            return new PriceBreakdown.PrecioPasajero(p.tipo().name(),
                    p.seatCategoria() == null ? null : p.seatCategoria().name(), conTemporada, descuento, recargo,
                    PricingRules.conDescuento(conTemporada, descuento).add(recargo));
        }).toList();
        BigDecimal total = lineas.stream().map(PriceBreakdown.PrecioPasajero::precio)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Instant salida = i.getFechaSalida();
        return new PriceBreakdown(i.getId(), desde, hasta, origen.getNombre(), destino.getNombre(),
                salida.plusSeconds(origen.getMinutosDesdeSalida() * 60L),
                salida.plusSeconds(destino.getMinutosDesdeSalida() * 60L), i.getMoneda(), base, conOferta, tramo,
                temporada.map(s -> new PriceBreakdown.TemporadaAplicada(s.getId(), s.getNombre(),
                        s.getMultiplicador())).orElse(null), lineas, total);
    }

    /** Temporada vigente en la fecha: la de la ruta gana a la de toda la empresa; entre iguales, la más reciente. */
    Optional<FareSeason> temporada(UUID companyId, UUID routeId, LocalDate fecha) {
        return seasonRepository.vigentes(companyId, routeId, fecha).stream().min(PRIORIDAD);
    }

    private Map<TipoPasajero, BigDecimal> descuentos(UUID companyId) {
        Map<TipoPasajero, BigDecimal> descuentos = new EnumMap<>(TipoPasajero.class);
        passengerTypeRepository.findByCompanyId(companyId)
                .forEach(t -> descuentos.put(t.getTipo(), t.getDescuentoPorcentaje()));
        return descuentos;
    }

    private Map<CategoriaAsiento, BigDecimal> recargos(UUID companyId) {
        Map<CategoriaAsiento, BigDecimal> recargos = new EnumMap<>(CategoriaAsiento.class);
        surchargeRepository.findByCompanyId(companyId).forEach(s -> recargos.put(s.getCategoria(), s.getRecargo()));
        return recargos;
    }

    private Itinerary buscar(UUID id) {
        return itineraryRepository.findDetailed(id)
                .orElseThrow(() -> ApiException.notFound("ITINERARY_NOT_FOUND", "El itinerario no existe"));
    }
}
