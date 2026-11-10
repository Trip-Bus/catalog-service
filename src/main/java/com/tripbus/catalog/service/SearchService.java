package com.tripbus.catalog.service;

import com.tripbus.catalog.client.SeatInventoryClient;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.common.observability.CatalogMetrics;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.web.dto.CalendarDay;
import com.tripbus.catalog.web.dto.PublicTripSearchResult;
import com.tripbus.catalog.web.dto.RoundTripResult;
import com.tripbus.catalog.web.dto.SearchCompany;
import com.tripbus.catalog.web.dto.TripSearchResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SearchService {

    public record SearchPage(List<TripSearchResult> content, long total) {}

    /** US-136: días que puede pedir el calendario. */
    static final int MAX_DIAS_CALENDARIO = 14;

    private final TripFinder tripFinder;
    private final RouteRepository routeRepository;
    private final SeatInventoryClient seatInventoryClient;
    private final SearchCache cache;
    private final Clock clock;
    private final CatalogMetrics metrics;

    public SearchService(
            TripFinder tripFinder,
            RouteRepository routeRepository,
            SeatInventoryClient seatInventoryClient,
            SearchCache cache,
            Clock clock,
            CatalogMetrics metrics) {
        this.tripFinder = tripFinder;
        this.routeRepository = routeRepository;
        this.seatInventoryClient = seatInventoryClient;
        this.cache = cache;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** US-007 + US-008 + US-014. Resultados ordenados por hora de salida. */
    @Transactional(readOnly = true)
    public List<TripSearchResult> search(String origin, String destination, LocalDate date) {
        return search(origin, destination, date, SearchFilters.ninguno()).content();
    }

    @Transactional(readOnly = true)
    public SearchPage search(String origin, String destination, LocalDate date, UUID companyId, String franja,
                             BigDecimal precioMin, BigDecimal precioMax, String tipoBus, String sort, String order,
                             int page, int size) {
        return search(origin, destination, date, new SearchFilters(companyId, franja, precioMin, precioMax, tipoBus,
                sort, order, page, size, null));
    }

    /**
     * HT-10: cada búsqueda se mide (tripbus_busqueda_segundos) y se cuenta según la cache (hit o miss).
     * US-138: con tramos (ver {@link SearchMode}) también trae los viajes en los que origen y destino son paradas
     * intermedias de otra ruta.
     */
    @Transactional(readOnly = true)
    public SearchPage search(String origin, String destination, LocalDate date, SearchFilters filtros) {
        return metrics.medirBusqueda(() -> buscar(origin, destination, date, filtros));
    }

    private SearchPage buscar(String origin, String destination, LocalDate date, SearchFilters f) {
        validarFiltros(f);
        String origen = TextNormalizer.normalize(origin);
        String destino = TextNormalizer.normalize(destination);
        validarCiudades(origen, destino);
        SearchMode modo = SearchMode.de(f.tramos());
        Optional<List<TripSearchResult>> cacheado = cache.get(origen, destino, date, modo.name());
        metrics.busqueda(cacheado.isPresent());
        List<TripSearchResult> base = cacheado.orElseGet(() -> {
            List<TripSearchResult> fromDb = queryDatabase(origen, destino, date, modo);
            cache.put(origen, destino, date, modo.name(), fromDb);
            return fromDb;
        });
        // Los viajes que ya salieron no se ofrecen (relevante si el resultado venía de cache).
        Instant now = clock.instant();
        List<TripSearchResult> vigentes = base.stream().filter(r -> r.salidaEfectiva().isAfter(now)).toList();
        Optional<Map<UUID, Integer>> disponibilidad =
                seatInventoryClient.availability(vigentes.stream().map(TripSearchResult::itineraryId).toList());
        List<TripSearchResult> filtered = vigentes.stream()
                .map(r -> conDisponibilidad(r, disponibilidad).sinOfertaVencida(now))
                .filter(r -> f.companyId() == null || f.companyId().equals(r.companyId()))
                .filter(r -> enFranja(r, f.franja()))
                .filter(r -> dentroPrecio(r, f.precioMin(), f.precioMax()))
                .filter(r -> f.tipoBus() == null || f.tipoBus().equals(r.tipoBus()))
                .sorted(orden(f.sort(), f.order())).toList();
        long total = filtered.size();
        long offset = (long) f.page() * f.size();
        List<TripSearchResult> content = offset >= total ? List.of()
                : filtered.subList((int) offset, (int) Math.min(total, offset + f.size()));
        return new SearchPage(content, total);
    }

    @Transactional(readOnly = true)
    public List<SearchCompany> companies(String origin, String destination, LocalDate date) {
        return search(origin, destination, date, null, null, null, null, null, "salida", "asc", 0, 100)
                .content().stream().collect(java.util.stream.Collectors.toMap(TripSearchResult::companyId,
                        r -> new SearchCompany(r.companyId(), r.empresa()), (a, b) -> a, java.util.TreeMap::new))
                .values().stream().toList();
    }

    /**
     * US-137: la ida (origin → destination en date) y la vuelta (destination → origin en returnDate), con los
     * mismos filtros y la misma lógica que /trips/search. returnDate no puede ser anterior a date.
     */
    @Transactional(readOnly = true)
    public RoundTripResult roundTrip(String origin, String destination, LocalDate date, LocalDate returnDate,
                                     SearchFilters filtros) {
        if (returnDate.isBefore(date)) {
            throw ApiException.badRequest("INVALID_SEARCH", "La fecha de regreso no puede ser anterior a la de ida");
        }
        return new RoundTripResult(publico(search(origin, destination, date, filtros)),
                publico(search(destination, origin, returnDate, filtros)));
    }

    private static List<PublicTripSearchResult> publico(SearchPage page) {
        return page.content().stream().map(PublicTripSearchResult::from).toList();
    }

    /**
     * US-136: por cada día desde {@code from} ({@code days} días, máximo 14), el precio más bajo para un adulto (con
     * la oferta activa si hay), el número de viajes y si alguno tiene cupo. Una sola consulta a la ocupación de
     * Seat Inventory; si no responde, hayCupo va en null (y la respuesta no se guarda en la cache de 60 s).
     */
    @Transactional(readOnly = true)
    public List<CalendarDay> calendar(String origin, String destination, LocalDate from, int days) {
        if (days < 1 || days > MAX_DIAS_CALENDARIO) {
            throw ApiException.badRequest("INVALID_SEARCH", "days debe estar entre 1 y " + MAX_DIAS_CALENDARIO);
        }
        String origen = TextNormalizer.normalize(origin);
        String destino = TextNormalizer.normalize(destination);
        validarCiudades(origen, destino);
        return cache.getCalendar(origen, destino, from, days).orElseGet(() -> {
            Instant now = clock.instant();
            Instant inicio = from.atStartOfDay(CatalogTime.ZONE).toInstant();
            Instant fin = from.plusDays(days).atStartOfDay(CatalogTime.ZONE).toInstant();
            // Solo los viajes que aún no salen (si el rango ya pasó, la consulta no trae nada).
            List<TripSearchResult> viajes = tripFinder.buscar(origen, destino, inicio.isAfter(now) ? inicio : now,
                    fin, SearchMode.AUTO);
            Map<UUID, SeatInventoryClient.Occupancy> ocupacion = seatInventoryClient.occupancy(
                    viajes.stream().map(TripSearchResult::itineraryId).distinct().toList()).orElse(null);
            List<CalendarDay> dias = dias(from, days, viajes, ocupacion);
            if (ocupacion != null) {
                cache.putCalendar(origen, destino, from, days, dias);
            }
            return dias;
        });
    }

    /** ocupacion null: Seat Inventory no respondió (hayCupo desconocido). */
    static List<CalendarDay> dias(LocalDate from, int days, List<TripSearchResult> viajes,
                                  Map<UUID, SeatInventoryClient.Occupancy> ocupacion) {
        Map<LocalDate, List<TripSearchResult>> porDia = viajes.stream().collect(Collectors.groupingBy(
                r -> LocalDate.ofInstant(r.salidaEfectiva(), CatalogTime.ZONE), TreeMap::new, Collectors.toList()));
        List<CalendarDay> dias = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            LocalDate fecha = from.plusDays(d);
            List<TripSearchResult> delDia = porDia.getOrDefault(fecha, List.of());
            BigDecimal minimo = delDia.stream().map(TripSearchResult::precioEfectivo).min(Comparator.naturalOrder())
                    .orElse(null);
            Boolean hayCupo = ocupacion == null ? null : delDia.stream().anyMatch(r -> conCupo(r, ocupacion));
            dias.add(new CalendarDay(fecha, minimo, delDia.size(), hayCupo));
        }
        return dias;
    }

    /** Seat Inventory omite los viajes que aún no conoce: nadie ha ocupado sus asientos, tienen cupo. */
    private static boolean conCupo(TripSearchResult r, Map<UUID, SeatInventoryClient.Occupancy> ocupacion) {
        SeatInventoryClient.Occupancy o = ocupacion.get(r.itineraryId());
        return o == null || o.disponibles() > 0;
    }

    private static void validarCiudades(String origen, String destino) {
        if (origen.isEmpty() || destino.isEmpty()) {
            throw ApiException.badRequest("INVALID_SEARCH", "Origen y destino son obligatorios");
        }
        if (origen.equals(destino)) {
            throw ApiException.badRequest("INVALID_SEARCH", "El origen y el destino deben ser diferentes");
        }
    }

    private static void validarFiltros(SearchFilters f) {
        BigDecimal min = f.precioMin();
        BigDecimal max = f.precioMax();
        if (f.franja() != null && !List.of("MANANA", "TARDE", "NOCHE").contains(f.franja())
                || min != null && min.signum() < 0 || max != null && max.signum() < 0
                || min != null && max != null && min.compareTo(max) > 0
                || f.tipoBus() != null
                    && !List.of(BusType.values()).stream().map(Enum::name).toList().contains(f.tipoBus())
                || !List.of("salida", "precio", "duracion").contains(f.sort())
                || !List.of("asc", "desc").contains(f.order()) || f.page() < 0 || f.size() < 1 || f.size() > 100) {
            throw ApiException.badRequest("INVALID_SEARCH", "Parámetros de búsqueda inválidos");
        }
    }

    private static boolean enFranja(TripSearchResult r, String franja) {
        if (franja == null) return true;
        LocalTime t = r.salidaEfectiva().atZone(CatalogTime.ZONE).toLocalTime();
        return switch (franja) {
            case "MANANA" -> !t.isBefore(LocalTime.of(5, 0)) && t.isBefore(LocalTime.of(12, 0));
            case "TARDE" -> !t.isBefore(LocalTime.of(12, 0)) && t.isBefore(LocalTime.of(18, 0));
            case "NOCHE" -> !t.isBefore(LocalTime.of(18, 0)) || t.isBefore(LocalTime.of(5, 0));
            default -> false;
        };
    }

    private static boolean dentroPrecio(TripSearchResult r, BigDecimal min, BigDecimal max) {
        BigDecimal price = r.precioEfectivo();
        return (min == null || price.compareTo(min) >= 0) && (max == null || price.compareTo(max) <= 0);
    }

    private static Comparator<TripSearchResult> orden(String sort, String order) {
        Comparator<TripSearchResult> c = switch (sort) {
            case "precio" -> Comparator.comparing(TripSearchResult::precioEfectivo);
            case "duracion" -> Comparator.comparingInt(TripSearchResult::duracionEfectiva);
            default -> Comparator.comparing(TripSearchResult::salidaEfectiva);
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

    private List<TripSearchResult> queryDatabase(String origen, String destino, LocalDate date, SearchMode modo) {
        Instant dayStart = date.atStartOfDay(CatalogTime.ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(CatalogTime.ZONE).toInstant();
        Instant now = clock.instant();
        Instant desde = dayStart.isAfter(now) ? dayStart : now;
        if (!desde.isBefore(dayEnd)) {
            return List.of();
        }
        return tripFinder.buscar(origen, destino, desde, dayEnd, modo);
    }
}
