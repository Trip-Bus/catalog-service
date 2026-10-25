package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.BusStatus;
import com.tripbus.catalog.domain.DiaSemana;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.ItineraryStatus;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.domain.RouteStatus;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.web.dto.CreateItineraryRequest;
import com.tripbus.catalog.web.dto.ItinerarySummary;
import com.tripbus.catalog.web.dto.PageResponse;
import com.tripbus.catalog.web.dto.ScheduleRequest;
import com.tripbus.catalog.web.dto.ScheduleResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * US-116/US-117: programación de viajes por la empresa.
 *
 * <p>Un bus nunca queda en dos viajes no cancelados que se crucen en [salida, llegada + alistamiento]
 * (tripbus.catalog.bus-turnaround). Para que dos solicitudes simultáneas no creen viajes cruzados, la fila
 * del bus se bloquea (SELECT ... FOR UPDATE) antes de revisar el cruce; además la restricción EXCLUDE de la
 * base (V5) impide cualquier cruce real.
 */
@Service
public class ItineraryPlanningService {

    public static final int MAX_DIAS = 90;
    public static final int MAX_VIAJES = 200;
    static final BigDecimal PRECIO_MAXIMO = new BigDecimal("10000000000");

    private final RouteRepository routeRepository;
    private final ItineraryRepository itineraryRepository;
    private final BusSchedule busSchedule;
    private final ItineraryPublisher itineraryPublisher;
    private final SearchCache searchCache;
    private final Clock clock;

    public ItineraryPlanningService(
            RouteRepository routeRepository,
            ItineraryRepository itineraryRepository,
            BusSchedule busSchedule,
            ItineraryPublisher itineraryPublisher,
            SearchCache searchCache,
            Clock clock) {
        this.routeRepository = routeRepository;
        this.itineraryRepository = itineraryRepository;
        this.busSchedule = busSchedule;
        this.itineraryPublisher = itineraryPublisher;
        this.searchCache = searchCache;
        this.clock = clock;
    }

    private record Contexto(Route route, Bus bus, BigDecimal precio) {
    }

    @Transactional
    public ItinerarySummary create(CreateItineraryRequest request, UUID companyScope) {
        Contexto ctx = preparar(request.routeId(), request.busId(), request.precioBase(), companyScope);
        Instant salida = request.fechaSalida();
        if (!salida.isAfter(clock.instant())) {
            throw ApiException.badRequest("DEPARTURE_IN_PAST", "La fecha de salida debe estar en el futuro");
        }
        Instant llegada = llegada(ctx.route(), salida);
        Optional<UUID> choque = busSchedule.findConflict(ctx.bus().getId(), salida, llegada);
        if (choque.isPresent()) {
            throw BusSchedule.conflictError(choque.get());
        }
        Itinerary itinerary = guardar(ctx, salida, llegada);
        itineraryPublisher.publishAfterCommit(List.of(itinerary));
        searchCache.evictAll();
        return ItinerarySummary.from(itinerary);
    }

    /** Crea lo que pueda: las fechas pasadas o con cruce se omiten con su motivo. */
    @Transactional
    public ScheduleResponse schedule(ScheduleRequest request, UUID companyScope) {
        List<LocalDate> fechas = fechas(request.desde(), request.hasta(), request.diasSemana());
        Contexto ctx = preparar(request.routeId(), request.busId(), request.precioBase(), companyScope);
        Instant ahora = clock.instant();
        List<Itinerary> creados = new ArrayList<>();
        List<ScheduleResponse.Omitido> omitidos = new ArrayList<>();
        for (LocalDate fecha : fechas) {
            Instant salida = fecha.atTime(request.horaSalida()).atZone(CatalogTime.ZONE).toInstant();
            if (!salida.isAfter(ahora)) {
                omitidos.add(new ScheduleResponse.Omitido(fecha, salida, "DEPARTURE_IN_PAST",
                        "La fecha de salida ya pasó", null));
                continue;
            }
            Instant llegada = llegada(ctx.route(), salida);
            Optional<UUID> choque = busSchedule.findConflict(ctx.bus().getId(), salida, llegada);
            if (choque.isPresent()) {
                omitidos.add(new ScheduleResponse.Omitido(fecha, salida, BusSchedule.CONFLICT,
                        BusSchedule.CONFLICT_MESSAGE, choque.get()));
                continue;
            }
            creados.add(guardar(ctx, salida, llegada));
        }
        itineraryPublisher.publishAfterCommit(creados);
        if (!creados.isEmpty()) {
            searchCache.evictAll();
        }
        return new ScheduleResponse(creados.stream().map(ItinerarySummary::from).toList(), omitidos);
    }

    @Transactional(readOnly = true)
    public PageResponse<ItinerarySummary> list(LocalDate desde, LocalDate hasta, UUID routeId, UUID busId,
                                               ItineraryStatus estado, UUID companyId, int page, int size,
                                               UUID companyScope) {
        UUID empresa = companyScope != null ? companyScope : companyId;
        Specification<Itinerary> spec = (root, query, cb) -> {
            List<Predicate> filtros = new ArrayList<>();
            if (empresa != null) {
                filtros.add(cb.equal(root.get("route").get("company").get("id"), empresa));
            }
            if (desde != null) {
                filtros.add(cb.greaterThanOrEqualTo(root.get("fechaSalida"),
                        desde.atStartOfDay(CatalogTime.ZONE).toInstant()));
            }
            if (hasta != null) {
                filtros.add(cb.lessThan(root.get("fechaSalida"),
                        hasta.plusDays(1).atStartOfDay(CatalogTime.ZONE).toInstant()));
            }
            if (routeId != null) {
                filtros.add(cb.equal(root.get("route").get("id"), routeId));
            }
            if (busId != null) {
                filtros.add(cb.equal(root.get("bus").get("id"), busId));
            }
            if (estado != null) {
                filtros.add(cb.equal(root.get("estado"), estado));
            }
            return cb.and(filtros.toArray(new Predicate[0]));
        };
        return PageResponse.of(
                itineraryRepository.findAll(spec, PageRequest.of(page, size, Sort.by("fechaSalida", "id"))),
                ItinerarySummary::from);
    }

    /** Fechas de [desde, hasta] cuyo día está en diasSemana. Máximo 90 días y 200 viajes. */
    static List<LocalDate> fechas(LocalDate desde, LocalDate hasta, Set<DiaSemana> dias) {
        if (hasta.isBefore(desde)) {
            throw ApiException.badRequest("INVALID_SCHEDULE", "hasta no puede ser anterior a desde");
        }
        if (ChronoUnit.DAYS.between(desde, hasta) + 1 > MAX_DIAS) {
            throw ApiException.badRequest("INVALID_SCHEDULE", "El rango no puede superar " + MAX_DIAS + " días");
        }
        Set<DayOfWeek> semana = EnumSet.noneOf(DayOfWeek.class);
        dias.forEach(d -> semana.add(d.dayOfWeek()));
        List<LocalDate> fechas = desde.datesUntil(hasta.plusDays(1)).filter(f -> semana.contains(f.getDayOfWeek()))
                .toList();
        if (fechas.size() > MAX_VIAJES) {
            throw ApiException.badRequest("INVALID_SCHEDULE", "No se pueden crear más de " + MAX_VIAJES + " viajes");
        }
        return fechas;
    }

    /** Validaciones comunes y bloqueo del bus (SELECT ... FOR UPDATE) hasta el fin de la transacción. */
    private Contexto preparar(UUID routeId, UUID busId, BigDecimal precio, UUID companyScope) {
        if (precio.signum() <= 0 || precio.scale() > 2 || precio.compareTo(PRECIO_MAXIMO) >= 0) {
            throw ApiException.badRequest("INVALID_PRICE",
                    "precioBase debe ser mayor que 0 COP, con máximo 2 decimales");
        }
        Route route = routeRepository.findWithCompany(routeId)
                .filter(r -> companyScope == null || companyScope.equals(r.getCompany().getId()))
                .orElseThrow(() -> ApiException.notFound("ROUTE_NOT_FOUND", "La ruta no existe"));
        Bus bus = busSchedule.lock(busId)
                .filter(b -> companyScope == null || companyScope.equals(b.getCompanyId()))
                .orElseThrow(() -> ApiException.notFound("BUS_NOT_FOUND", "El bus no existe"));
        if (!bus.getCompanyId().equals(route.getCompany().getId())) {
            throw ApiException.badRequest("COMPANY_MISMATCH", "La ruta y el bus deben ser de la misma empresa");
        }
        CompanyService.requireActive(route.getCompany());
        if (route.getEstado() != RouteStatus.ACTIVA) {
            throw ApiException.conflict("ROUTE_INACTIVE", "La ruta está inactiva");
        }
        if (bus.getEstado() != BusStatus.ACTIVO) {
            throw ApiException.conflict("BUS_NOT_ACTIVE", "El bus no está ACTIVO (" + bus.getEstado() + ")");
        }
        return new Contexto(route, bus, precio);
    }

    static Instant llegada(Route route, Instant salida) {
        return salida.plus(route.getDuracionMinutos(), ChronoUnit.MINUTES);
    }

    private Itinerary guardar(Contexto ctx, Instant salida, Instant llegada) {
        Itinerary itinerary = new Itinerary(UUID.randomUUID(), ctx.route(), ctx.bus(), salida, llegada, ctx.precio());
        try {
            return itineraryRepository.saveAndFlush(itinerary);
        } catch (DataIntegrityViolationException e) {
            // Defensa de la base (EXCLUDE ex_itineraries_bus_sin_cruce): no debería pasar con el bloqueo.
            throw BusSchedule.conflictError();
        }
    }
}
