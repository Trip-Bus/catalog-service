package com.tripbus.catalog.service;

import com.tripbus.catalog.client.SeatInventoryClient;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.web.dto.OfertaResumen;
import com.tripbus.catalog.web.dto.TripSearchResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.web.dto.SearchCompany;

@Service
public class SearchService {
    public record SearchPage(List<TripSearchResult> content, long total) {}

    private final ItineraryRepository itineraryRepository;
    private final RouteRepository routeRepository;
    private final SeatInventoryClient seatInventoryClient;
    private final SearchCache cache;
    private final Clock clock;

    public SearchService(
            ItineraryRepository itineraryRepository,
            RouteRepository routeRepository,
            SeatInventoryClient seatInventoryClient,
            SearchCache cache,
            Clock clock) {
        this.itineraryRepository = itineraryRepository;
        this.routeRepository = routeRepository;
        this.seatInventoryClient = seatInventoryClient;
        this.cache = cache;
        this.clock = clock;
    }

    /** US-007 + US-008 + US-014. Resultados ordenados por hora de salida. */
    @Transactional(readOnly = true)
    public List<TripSearchResult> search(String origin, String destination, LocalDate date) {
        return search(origin, destination, date, null, null, null, null, null, "salida", "asc", 0, 50).content();
    }

    @Transactional(readOnly = true)
    public SearchPage search(String origin, String destination, LocalDate date, UUID companyId, String franja,
                             BigDecimal precioMin, BigDecimal precioMax, String tipoBus, String sort, String order,
                             int page, int size) {
        validarFiltros(franja, precioMin, precioMax, tipoBus, sort, order, page, size);
        String origen = TextNormalizer.normalize(origin);
        String destino = TextNormalizer.normalize(destination);
        if (origen.isEmpty() || destino.isEmpty()) {
            throw ApiException.badRequest("INVALID_SEARCH", "Origen y destino son obligatorios");
        }
        if (origen.equals(destino)) {
            throw ApiException.badRequest("INVALID_SEARCH", "El origen y el destino deben ser diferentes");
        }

        List<TripSearchResult> base = cache.get(origen, destino, date).orElseGet(() -> {
            List<TripSearchResult> fromDb = queryDatabase(origen, destino, date);
            cache.put(origen, destino, date, fromDb);
            return fromDb;
        });

        // Los viajes que ya salieron no se ofrecen (relevante si el resultado venía de cache).
        Instant now = clock.instant();
        List<TripSearchResult> vigentes = base.stream().filter(r -> r.fechaSalida().isAfter(now)).toList();

        Optional<Map<UUID, Integer>> disponibilidad =
                seatInventoryClient.availability(vigentes.stream().map(TripSearchResult::itineraryId).toList());
        List<TripSearchResult> filtered = vigentes.stream()
                .map(r -> conDisponibilidad(r, disponibilidad).sinOfertaVencida(now))
                .filter(r -> companyId == null || companyId.equals(r.companyId()))
                .filter(r -> enFranja(r, franja))
                .filter(r -> dentroPrecio(r, precioMin, precioMax))
                .filter(r -> tipoBus == null || tipoBus.equals(r.tipoBus()))
                .sorted(orden(sort, order)).toList();
        long total = filtered.size();
        long offset = (long) page * size;
        List<TripSearchResult> content = offset >= total ? List.of()
                : filtered.subList((int) offset, (int) Math.min(total, offset + size));
        return new SearchPage(content, total);
    }

    @Transactional(readOnly = true)
    public List<SearchCompany> companies(String origin, String destination, LocalDate date) {
        return search(origin, destination, date, null, null, null, null, null, "salida", "asc", 0, 100)
                .content().stream().collect(java.util.stream.Collectors.toMap(TripSearchResult::companyId,
                        r -> new SearchCompany(r.companyId(), r.empresa()), (a, b) -> a, java.util.TreeMap::new))
                .values().stream().toList();
    }

    private static void validarFiltros(String franja, BigDecimal min, BigDecimal max, String tipo, String sort,
                                       String order, int page, int size) {
        if (franja != null && !List.of("MANANA", "TARDE", "NOCHE").contains(franja)
                || min != null && min.signum() < 0 || max != null && max.signum() < 0
                || min != null && max != null && min.compareTo(max) > 0
                || tipo != null && !List.of(BusType.values()).stream().map(Enum::name).toList().contains(tipo)
                || !List.of("salida", "precio", "duracion").contains(sort)
                || !List.of("asc", "desc").contains(order) || page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_SEARCH", "Parámetros de búsqueda inválidos");
        }
    }

    private static boolean enFranja(TripSearchResult r, String franja) {
        if (franja == null) return true;
        LocalTime t = r.fechaSalida().atZone(ZoneId.of("America/Bogota")).toLocalTime();
        return switch (franja) {
            case "MANANA" -> !t.isBefore(LocalTime.of(5, 0)) && t.isBefore(LocalTime.of(12, 0));
            case "TARDE" -> !t.isBefore(LocalTime.of(12, 0)) && t.isBefore(LocalTime.of(18, 0));
            case "NOCHE" -> !t.isBefore(LocalTime.of(18, 0)) || t.isBefore(LocalTime.of(5, 0));
            default -> false;
        };
    }

    private static boolean dentroPrecio(TripSearchResult r, BigDecimal min, BigDecimal max) {
        BigDecimal price = r.ofertaActiva() == null ? r.precioBase() : r.ofertaActiva().precioOferta();
        return (min == null || price.compareTo(min) >= 0) && (max == null || price.compareTo(max) <= 0);
    }

    private static Comparator<TripSearchResult> orden(String sort, String order) {
        Comparator<TripSearchResult> c = switch (sort) {
            case "precio" -> Comparator.comparing(r -> r.ofertaActiva() == null ? r.precioBase() : r.ofertaActiva().precioOferta());
            case "duracion" -> Comparator.comparingInt(TripSearchResult::duracionMinutos);
            default -> Comparator.comparing(TripSearchResult::fechaSalida);
        };
        return order.equals("desc") ? c.reversed() : c;
    }

    /**
     * Seat Inventory inicializa un itinerario la primera vez que alguien ocupa uno de sus asientos.
     * Si respondió sin ese itinerario, todos sus asientos están libres. Si falló, no hay dato.
     */
    static TripSearchResult conDisponibilidad(TripSearchResult r, Optional<Map<UUID, Integer>> disponibilidad) {
        return disponibilidad
                .map(mapa -> r.withDisponibilidad(mapa.getOrDefault(r.itineraryId(), r.capacidad())))
                .orElseGet(() -> r.withDisponibilidad(null));
    }

    @Transactional(readOnly = true)
    public List<String> cities() {
        TreeSet<String> cities = new TreeSet<>(routeRepository.findOrigenes());
        cities.addAll(routeRepository.findDestinos());
        return List.copyOf(cities);
    }

    private List<TripSearchResult> queryDatabase(String origen, String destino, LocalDate date) {
        Instant dayStart = date.atStartOfDay(CatalogTime.ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(CatalogTime.ZONE).toInstant();
        Instant now = clock.instant();
        Instant desde = dayStart.isAfter(now) ? dayStart : now;
        if (!desde.isBefore(dayEnd)) {
            return List.of();
        }
        return itineraryRepository.search(origen, destino, desde, dayEnd).stream()
                .map(i -> toResult(i, now))
                .toList();
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
                i.getRoute().getCompany().getId());
    }
}
