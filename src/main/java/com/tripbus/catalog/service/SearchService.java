package com.tripbus.catalog.service;

import com.tripbus.catalog.client.SeatInventoryClient;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.common.observability.CatalogMetrics;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.repository.StopRepository;
import com.tripbus.catalog.repository.TerminalDeSalida;
import com.tripbus.catalog.web.dto.CalendarDay;
import com.tripbus.catalog.web.dto.PublicTripSearchResult;
import com.tripbus.catalog.web.dto.RoundTripResult;
import com.tripbus.catalog.web.dto.SearchCompany;
import com.tripbus.catalog.web.dto.SearchFacets;
import com.tripbus.catalog.web.dto.TripSearchResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Búsqueda (US-007), calendario (US-136), facetas e ida y vuelta (US-137). Las tres primeras parten del mismo
 * conjunto de viajes: los de {@link TripFinder} que aún no salen, sin ofertas vencidas y, si se pide, con la
 * disponibilidad de Seat Inventory; filtrados por empresa, franja y tipo de bus, y con el mismo precio "desde"
 * ({@link TripSearchResult#precioDesde()}).
 */
@Service
public class SearchService {

    public record SearchPage(List<TripSearchResult> content, long total) {}

    /** Los viajes de un día y si salieron de la cache de búsquedas. */
    private record Catalogo(List<TripSearchResult> viajes, boolean deCache) {}

    /** Viajes vigentes con su disponibilidad; disponibilidadConocida = Seat Inventory respondió. */
    private record Vigentes(List<TripSearchResult> viajes, boolean disponibilidadConocida) {}

    /** US-136: días que puede pedir el calendario. */
    static final int MAX_DIAS_CALENDARIO = 14;

    /** Facetas: días hacia adelante en los que se buscan las empresas que cubren la ruta. */
    static final int DIAS_EMPRESAS = 14;

    private static final String SIN_FILTRO = "-";

    private final TripFinder tripFinder;
    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final SeatInventoryClient seatInventoryClient;
    private final SearchCache cache;
    private final Clock clock;
    private final CatalogMetrics metrics;

    public SearchService(
            TripFinder tripFinder,
            RouteRepository routeRepository,
            StopRepository stopRepository,
            SeatInventoryClient seatInventoryClient,
            SearchCache cache,
            Clock clock,
            CatalogMetrics metrics) {
        this.tripFinder = tripFinder;
        this.routeRepository = routeRepository;
        this.stopRepository = stopRepository;
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
        Catalogo catalogo = catalogo(origen, destino, date, SearchMode.de(f.tramos()));
        metrics.busqueda(catalogo.deCache());
        List<TripSearchResult> filtered = vigentes(catalogo.viajes()).viajes().stream()
                .filter(r -> coincide(r, f))
                .filter(r -> dentroPrecio(r, f.precioMin(), f.precioMax()))
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
     * Facetas de una búsqueda: precioMin/precioMax = mínimo y máximo precioDesde de los viajes vigentes del día con
     * los filtros (sin el de precio); empresas = todas las que cubren la ruta en los próximos {@value #DIAS_EMPRESAS}
     * días (y las del día pedido), cada una con su terminal de salida en el origen y sus salidas del día (con franja
     * y tipoBus: las mismas que daría /trips/search con su companyId); tiposBus = los del día (con empresa y franja).
     */
    @Transactional(readOnly = true)
    public SearchFacets facets(String origin, String destination, LocalDate date, SearchFilters f) {
        validarFiltros(f);
        String origen = TextNormalizer.normalize(origin);
        String destino = TextNormalizer.normalize(destination);
        validarCiudades(origen, destino);
        SearchMode modo = SearchMode.de(f.tramos());
        Instant now = clock.instant();
        List<TripSearchResult> delDia = futuros(catalogo(origen, destino, date, modo).viajes(), now);

        List<BigDecimal> precios = delDia.stream().filter(r -> coincide(r, f)).map(TripSearchResult::precioDesde)
                .toList();
        List<String> tiposBus = delDia.stream()
                .filter(r -> deEmpresa(r, f.companyId()) && enFranja(r, f.franja()))
                .map(TripSearchResult::tipoBus).distinct().sorted().toList();

        Map<UUID, String> nombres = new LinkedHashMap<>();
        tripFinder.buscar(origen, destino, now, now.plus(Duration.ofDays(DIAS_EMPRESAS)), modo)
                .forEach(r -> nombres.putIfAbsent(r.companyId(), r.empresa()));
        delDia.forEach(r -> nombres.putIfAbsent(r.companyId(), r.empresa()));
        Map<UUID, Long> salidas = delDia.stream()
                .filter(r -> enFranja(r, f.franja()) && deTipo(r, f.tipoBus()))
                .collect(Collectors.groupingBy(TripSearchResult::companyId, Collectors.counting()));
        Map<UUID, String> terminales = nombres.isEmpty() ? Map.of()
                : stopRepository.terminalesDeSalida(origen, nombres.keySet()).stream()
                        .collect(Collectors.toMap(TerminalDeSalida::companyId, TerminalDeSalida::terminal,
                                (a, b) -> a));
        List<SearchFacets.Empresa> empresas = nombres.entrySet().stream()
                .map(e -> new SearchFacets.Empresa(e.getKey(), e.getValue(), terminales.get(e.getKey()),
                        salidas.getOrDefault(e.getKey(), 0L).intValue()))
                .sorted(Comparator.comparing(SearchFacets.Empresa::nombre)
                        .thenComparing(SearchFacets.Empresa::id))
                .toList();
        return new SearchFacets(precios.stream().min(Comparator.naturalOrder()).orElse(null),
                precios.stream().max(Comparator.naturalOrder()).orElse(null), empresas, tiposBus);
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

    /** US-136 sin filtros (modo AUTO). */
    @Transactional(readOnly = true)
    public List<CalendarDay> calendar(String origin, String destination, LocalDate from, int days) {
        return calendar(origin, destination, from, days, SearchFilters.ninguno());
    }

    /**
     * US-136: por cada día desde {@code from} ({@code days} días, máximo 14), los mismos viajes que daría
     * /trips/search ese día con los mismos tramos, companyId, franja y tipoBus: viajes = su total y precioMinimo = el
     * menor precioDesde de los que tienen cupo (agotado != true). Una sola consulta a Seat Inventory; si no
     * responde, hayCupo va en null y la respuesta no se guarda en la cache de 60 s (cuya llave lleva los filtros).
     */
    @Transactional(readOnly = true)
    public List<CalendarDay> calendar(String origin, String destination, LocalDate from, int days, SearchFilters f) {
        if (days < 1 || days > MAX_DIAS_CALENDARIO) {
            throw ApiException.badRequest("INVALID_SEARCH", "days debe estar entre 1 y " + MAX_DIAS_CALENDARIO);
        }
        validarFiltros(f);
        String origen = TextNormalizer.normalize(origin);
        String destino = TextNormalizer.normalize(destination);
        validarCiudades(origen, destino);
        SearchMode modo = SearchMode.de(f.tramos());
        String llave = String.join(":", modo.name(), f.companyId() == null ? SIN_FILTRO : f.companyId().toString(),
                f.franja() == null ? SIN_FILTRO : f.franja(), f.tipoBus() == null ? SIN_FILTRO : f.tipoBus());
        return cache.getCalendar(origen, destino, from, days, llave).orElseGet(() -> {
            Instant now = clock.instant();
            Instant inicio = from.atStartOfDay(CatalogTime.ZONE).toInstant();
            Instant fin = from.plusDays(days).atStartOfDay(CatalogTime.ZONE).toInstant();
            // Solo los viajes que aún no salen (si el rango ya pasó, la consulta no trae nada).
            Vigentes vigentes = vigentes(tripFinder.buscar(origen, destino, inicio.isAfter(now) ? inicio : now, fin,
                    modo));
            List<CalendarDay> dias = dias(from, days,
                    vigentes.viajes().stream().filter(r -> coincide(r, f)).toList(),
                    vigentes.disponibilidadConocida());
            if (vigentes.disponibilidadConocida()) {
                cache.putCalendar(origen, destino, from, days, llave, dias);
            }
            return dias;
        });
    }

    /** Agrupa por el día de salida del pasajero en America/Bogota, igual que la búsqueda de cada día. */
    static List<CalendarDay> dias(LocalDate from, int days, List<TripSearchResult> viajes, boolean cupoConocido) {
        Map<LocalDate, List<TripSearchResult>> porDia = viajes.stream().collect(Collectors.groupingBy(
                r -> LocalDate.ofInstant(r.salidaEfectiva(), CatalogTime.ZONE), TreeMap::new, Collectors.toList()));
        List<CalendarDay> dias = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            LocalDate fecha = from.plusDays(d);
            List<TripSearchResult> delDia = porDia.getOrDefault(fecha, List.of());
            BigDecimal minimo = delDia.stream().filter(r -> !Boolean.TRUE.equals(r.agotado()))
                    .map(TripSearchResult::precioDesde).min(Comparator.naturalOrder()).orElse(null);
            Boolean hayCupo = cupoConocido ? delDia.stream().anyMatch(r -> Boolean.FALSE.equals(r.agotado())) : null;
            dias.add(new CalendarDay(fecha, minimo, delDia.size(), hayCupo));
        }
        return dias;
    }

    /** Los viajes del día, de la cache de búsquedas (45 s) o de la base. */
    private Catalogo catalogo(String origen, String destino, LocalDate date, SearchMode modo) {
        Optional<List<TripSearchResult>> cacheado = cache.get(origen, destino, date, modo.name());
        if (cacheado.isPresent()) {
            return new Catalogo(cacheado.get(), true);
        }
        List<TripSearchResult> fromDb = queryDatabase(origen, destino, date, modo);
        cache.put(origen, destino, date, modo.name(), fromDb);
        return new Catalogo(fromDb, false);
    }

    /** Los que aún no salen (relevante si venían de cache), sin ofertas vencidas. */
    private static List<TripSearchResult> futuros(List<TripSearchResult> viajes, Instant now) {
        return viajes.stream().filter(r -> r.salidaEfectiva().isAfter(now)).map(r -> r.sinOfertaVencida(now))
                .toList();
    }

    /** Los futuros con la disponibilidad de Seat Inventory (una sola consulta). */
    private Vigentes vigentes(List<TripSearchResult> viajes) {
        List<TripSearchResult> futuros = futuros(viajes, clock.instant());
        Optional<Map<UUID, Integer>> disponibilidad =
                seatInventoryClient.availability(futuros.stream().map(TripSearchResult::itineraryId).toList());
        return new Vigentes(futuros.stream().map(r -> conDisponibilidad(r, disponibilidad)).toList(),
                disponibilidad.isPresent());
    }

    /** Filtros de empresa, franja y tipo de bus (todos salvo el precio). */
    private static boolean coincide(TripSearchResult r, SearchFilters f) {
        return deEmpresa(r, f.companyId()) && enFranja(r, f.franja()) && deTipo(r, f.tipoBus());
    }

    private static boolean deEmpresa(TripSearchResult r, UUID companyId) {
        return companyId == null || companyId.equals(r.companyId());
    }

    private static boolean deTipo(TripSearchResult r, String tipoBus) {
        return tipoBus == null || tipoBus.equals(r.tipoBus());
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

    /** precioMin/precioMax comparan el precio "desde" (el mismo que muestran las tarjetas). */
    private static boolean dentroPrecio(TripSearchResult r, BigDecimal min, BigDecimal max) {
        BigDecimal price = r.precioDesde();
        return (min == null || price.compareTo(min) >= 0) && (max == null || price.compareTo(max) <= 0);
    }

    private static Comparator<TripSearchResult> orden(String sort, String order) {
        Comparator<TripSearchResult> c = switch (sort) {
            case "precio" -> Comparator.comparing(TripSearchResult::precioDesde);
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
