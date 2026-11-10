package com.tripbus.catalog.service;

import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.SegmentPrice;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.repository.SegmentPriceRepository;
import com.tripbus.catalog.repository.StopRepository;
import com.tripbus.catalog.repository.TramoParada;
import com.tripbus.catalog.web.dto.OfertaResumen;
import com.tripbus.catalog.web.dto.TripSearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Viajes entre dos ciudades (ya normalizadas) cuya salida del pasajero cae en [desde, hasta), ordenados por esa
 * salida. Los directos salen de la ruta (origen y destino de la ruta); US-138: los tramos, de las paradas
 * intermedias de otras rutas, con el precio del tramo (sin temporada ni tipo de pasajero, ver {@link PricingRules}).
 * Lo usan la búsqueda (US-007), el calendario (US-136) y la ida y vuelta (US-137).
 */
@Component
public class TripFinder {

    private final ItineraryRepository itineraryRepository;
    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final SegmentPriceRepository segmentPriceRepository;
    private final Clock clock;
    private final int minPorcentaje;

    public TripFinder(ItineraryRepository itineraryRepository, RouteRepository routeRepository,
                      StopRepository stopRepository, SegmentPriceRepository segmentPriceRepository, Clock clock,
                      @Value("${tripbus.catalog.min-segment-percent:30}") int minPorcentaje) {
        this.itineraryRepository = itineraryRepository;
        this.routeRepository = routeRepository;
        this.stopRepository = stopRepository;
        this.segmentPriceRepository = segmentPriceRepository;
        this.clock = clock;
        this.minPorcentaje = minPorcentaje;
    }

    public List<TripSearchResult> buscar(String origen, String destino, Instant desde, Instant hasta, SearchMode modo) {
        Instant now = clock.instant();
        List<TripSearchResult> resultados = new ArrayList<>(itineraryRepository.search(origen, destino, desde, hasta)
                .stream().map(i -> toResult(i, now)).toList());
        boolean conTramos = switch (modo) {
            case TODOS -> true;
            case DIRECTOS -> false;
            case AUTO -> !routeRepository.existsByOrigenNormAndDestinoNorm(origen, destino);
        };
        if (conTramos) {
            resultados.addAll(tramos(origen, destino, desde, hasta, now));
            resultados.sort(Comparator.comparing(TripSearchResult::salidaEfectiva));
        }
        return resultados;
    }

    private List<TripSearchResult> tramos(String origen, String destino, Instant desde, Instant hasta, Instant now) {
        Map<UUID, List<TramoParada>> porRuta = stopRepository.tramos(origen, destino).stream()
                .filter(t -> !t.esCompleto())
                .collect(Collectors.groupingBy(TramoParada::routeId));
        if (porRuta.isEmpty()) {
            return List.of();
        }
        int maxMinutos = porRuta.values().stream().flatMap(List::stream).mapToInt(TramoParada::minutosDesde).max()
                .orElse(0);
        Map<String, BigDecimal> exactos = segmentPriceRepository.findByRouteIdIn(porRuta.keySet()).stream()
                .collect(Collectors.toMap(p -> clave(p.getRouteId(), p.getDesdeOrden(), p.getHastaOrden()),
                        SegmentPrice::getPrecio));
        List<TripSearchResult> resultados = new ArrayList<>();
        for (Itinerary i : itineraryRepository.searchByRoutes(porRuta.keySet(),
                desde.minus(Duration.ofMinutes(maxMinutos)), hasta)) {
            for (TramoParada t : porRuta.get(i.getRoute().getId())) {
                Instant salida = i.getFechaSalida().plus(Duration.ofMinutes(t.minutosDesde()));
                if (!salida.isBefore(desde) && salida.isBefore(hasta)) {
                    resultados.add(tramo(i, t, salida, exactos, now));
                }
            }
        }
        return resultados;
    }

    private TripSearchResult tramo(Itinerary i, TramoParada t, Instant salida, Map<String, BigDecimal> exactos,
                                   Instant now) {
        boolean conOferta = i.tieneOfertaActiva(now);
        BigDecimal base = conOferta ? i.getPrecioOferta() : i.getPrecioBase();
        BigDecimal exacto = conOferta ? null : exactos.get(clave(t.routeId(), t.fromOrden(), t.toOrden()));
        BigDecimal precio = PricingRules.tramo(base, t.minutosDesde(), t.minutosHasta(), t.duracionRuta(), exacto,
                minPorcentaje);
        return toResult(i, now).conTramo(t.fromOrden(), t.toOrden(), salida,
                i.getFechaSalida().plus(Duration.ofMinutes(t.minutosHasta())), precio);
    }

    static String clave(UUID routeId, int desde, int hasta) {
        return routeId + ":" + desde + ":" + hasta;
    }

    static TripSearchResult toResult(Itinerary i, Instant now) {
        OfertaResumen oferta = i.tieneOfertaActiva(now)
                ? new OfertaResumen(i.getOfertaId(), i.getPrecioOferta(), i.getOfertaFin())
                : null;
        return new TripSearchResult(
                i.getId(),
                i.getRoute().getId(),
                i.getBus().getId(),
                i.getRoute().getCompany().getNombre(),
                i.getRoute().getOrigen(),
                i.getRoute().getDestino(),
                i.getFechaSalida(),
                i.getFechaLlegadaEstimada(),
                i.getRoute().getDuracionMinutos(),
                i.getPrecioBase(),
                i.getMoneda(),
                i.getBus().getTipo().name(),
                i.getBus().getCapacidad(),
                null,
                null,
                oferta,
                false,
                i.getRoute().getCompany().getId(),
                i.getBus().getServicios().stream().map(Enum::name).toList(),
                null, null, null, null, null);
    }
}
