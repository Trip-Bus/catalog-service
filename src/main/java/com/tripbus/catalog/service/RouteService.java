package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.common.events.EventPublisher;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.domain.RouteStatus;
import com.tripbus.catalog.domain.Stop;
import com.tripbus.catalog.events.CatalogEvents;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.repository.StopRepository;
import com.tripbus.catalog.web.dto.PageResponse;
import com.tripbus.catalog.web.dto.RouteRequest;
import com.tripbus.catalog.web.dto.RouteResponse;
import com.tripbus.catalog.web.dto.StopRequest;
import com.tripbus.catalog.web.dto.StopResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * US-114: rutas y paradas de una empresa. {@code companyScope} es la empresa del OPERADOR o null para el
 * ADMIN; una ruta de otra empresa responde 404. Una empresa INACTIVA no crea ni edita rutas.
 */
@Service
public class RouteService {

    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final ItineraryRepository itineraryRepository;
    private final CompanyService companyService;
    private final EventPublisher eventPublisher;
    private final SearchCache searchCache;
    private final Clock clock;

    public RouteService(
            RouteRepository routeRepository,
            StopRepository stopRepository,
            ItineraryRepository itineraryRepository,
            CompanyService companyService,
            EventPublisher eventPublisher,
            SearchCache searchCache,
            Clock clock) {
        this.routeRepository = routeRepository;
        this.stopRepository = stopRepository;
        this.itineraryRepository = itineraryRepository;
        this.companyService = companyService;
        this.eventPublisher = eventPublisher;
        this.searchCache = searchCache;
        this.clock = clock;
    }

    @Transactional
    public RouteResponse create(RouteRequest request, UUID companyScope) {
        UUID companyId = CompanyService.resolveCompany(request.companyId(), companyScope);
        Company company = companyService.requireActive(companyId);
        int duracion = RouteValidator.validar(request.origen(), request.destino(), request.paradas());
        Route route = routeRepository.save(new Route(UUID.randomUUID(), company, request.origen().trim(),
                request.destino().trim(), duracion));
        List<Stop> paradas = guardarParadas(route, request.paradas());
        eventPublisher.publishAfterCommit(CatalogEvents.ROUTE_CREATED, payload(route, paradas));
        return RouteResponse.from(route, toResponse(paradas));
    }

    @Transactional(readOnly = true)
    public PageResponse<RouteResponse> list(RouteStatus estado, String origen, String destino, UUID companyId,
                                            int page, int size, UUID companyScope) {
        UUID empresa = companyScope != null ? companyScope : companyId;
        String origenNorm = TextNormalizer.normalize(origen);
        String destinoNorm = TextNormalizer.normalize(destino);
        Specification<Route> spec = (root, query, cb) -> {
            List<Predicate> filtros = new ArrayList<>();
            if (empresa != null) {
                filtros.add(cb.equal(root.get("company").get("id"), empresa));
            }
            if (estado != null) {
                filtros.add(cb.equal(root.get("estado"), estado));
            }
            if (!origenNorm.isEmpty()) {
                filtros.add(cb.equal(root.get("origenNorm"), origenNorm));
            }
            if (!destinoNorm.isEmpty()) {
                filtros.add(cb.equal(root.get("destinoNorm"), destinoNorm));
            }
            return cb.and(filtros.toArray(new Predicate[0]));
        };
        return PageResponse.of(routeRepository.findAll(spec, PageRequest.of(page, size, Sort.by("origen", "destino", "id"))),
                r -> RouteResponse.from(r, null));
    }

    @Transactional(readOnly = true)
    public RouteResponse get(UUID id, UUID companyScope) {
        Route route = find(id, companyScope);
        return RouteResponse.from(route, toResponse(stopRepository.findByRouteIdOrderByOrdenAsc(id)));
    }

    /** Reemplaza origen, destino y paradas. 409 ROUTE_IN_USE si cambia algo y la ruta tiene viajes futuros. */
    @Transactional
    public RouteResponse update(UUID id, RouteRequest request, UUID companyScope) {
        Route route = find(id, companyScope);
        CompanyService.requireActive(route.getCompany());
        int duracion = RouteValidator.validar(request.origen(), request.destino(), request.paradas());
        return reemplazar(route, request.origen().trim(), request.destino().trim(), duracion, request.paradas());
    }

    /** PUT /routes/{id}/stops: reemplaza la lista completa; origen y destino de la ruta no cambian. */
    @Transactional
    public RouteResponse replaceStops(UUID id, List<StopRequest> paradas, UUID companyScope) {
        Route route = find(id, companyScope);
        CompanyService.requireActive(route.getCompany());
        int duracion = RouteValidator.validar(route.getOrigen(), route.getDestino(), paradas);
        return reemplazar(route, route.getOrigen(), route.getDestino(), duracion, paradas);
    }

    @Transactional
    public RouteResponse changeStatus(UUID id, RouteStatus estado, UUID companyScope) {
        Route route = find(id, companyScope);
        List<Stop> paradas = stopRepository.findByRouteIdOrderByOrdenAsc(id);
        if (route.getEstado() != estado) {
            route.cambiarEstado(estado);
            eventPublisher.publishAfterCommit(CatalogEvents.ROUTE_UPDATED, payload(route, paradas));
        }
        return RouteResponse.from(route, toResponse(paradas));
    }

    private RouteResponse reemplazar(Route route, String origen, String destino, int duracion,
                                     List<StopRequest> nuevas) {
        List<Stop> actuales = stopRepository.findByRouteIdOrderByOrdenAsc(route.getId());
        boolean cambia = !origen.equals(route.getOrigen()) || !destino.equals(route.getDestino())
                || !mismasParadas(actuales, nuevas);
        if (!cambia) {
            return RouteResponse.from(route, toResponse(actuales));
        }
        if (itineraryRepository.existsFutureForRoute(route.getId(), clock.instant())) {
            throw ApiException.conflict("ROUTE_IN_USE",
                    "La ruta tiene viajes futuros no cancelados y no puede cambiar sus paradas");
        }
        route.actualizar(origen, destino, duracion);
        stopRepository.deleteByRouteId(route.getId());
        List<Stop> paradas = guardarParadas(route, nuevas);
        eventPublisher.publishAfterCommit(CatalogEvents.ROUTE_UPDATED, payload(route, paradas));
        // Origen y destino salen en los resultados de búsqueda cacheados.
        searchCache.evictAll();
        return RouteResponse.from(route, toResponse(paradas));
    }

    static boolean mismasParadas(List<Stop> actuales, List<StopRequest> nuevas) {
        if (actuales.size() != nuevas.size()) {
            return false;
        }
        for (int i = 0; i < actuales.size(); i++) {
            Stop a = actuales.get(i);
            StopRequest n = nuevas.get(i);
            if (!a.getNombre().equals(n.nombre().trim()) || Double.compare(a.getLat(), n.lat()) != 0
                    || Double.compare(a.getLng(), n.lng()) != 0 || a.getMinutosDesdeSalida() != n.minutosDesdeSalida()) {
                return false;
            }
        }
        return true;
    }

    private List<Stop> guardarParadas(Route route, List<StopRequest> paradas) {
        List<Stop> stops = new ArrayList<>();
        for (StopRequest p : paradas) {
            stops.add(new Stop(UUID.randomUUID(), route.getId(), p.orden(), p.nombre().trim(), p.lat(), p.lng(),
                    p.minutosDesdeSalida()));
        }
        return stopRepository.saveAll(stops);
    }

    private Route find(UUID id, UUID companyScope) {
        return routeRepository.findWithCompany(id)
                .filter(r -> companyScope == null || companyScope.equals(r.getCompany().getId()))
                .orElseThrow(() -> ApiException.notFound("ROUTE_NOT_FOUND", "La ruta no existe"));
    }

    private static List<StopResponse> toResponse(List<Stop> paradas) {
        return paradas.stream()
                .map(s -> new StopResponse(s.getId(), s.getOrden(), s.getNombre(), s.getLat(), s.getLng(),
                        s.getMinutosDesdeSalida(), null))
                .toList();
    }

    /** RouteCreated / RouteUpdated: fleet los usará en el Sprint 3 para conocer las paradas. */
    private static Map<String, Object> payload(Route r, List<Stop> paradas) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("routeId", r.getId());
        payload.put("companyId", r.getCompany().getId());
        payload.put("origen", r.getOrigen());
        payload.put("destino", r.getDestino());
        payload.put("duracionMinutos", r.getDuracionMinutos());
        payload.put("estado", r.getEstado().name());
        payload.put("paradas", paradas.stream().map(s -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("orden", s.getOrden());
            p.put("nombre", s.getNombre());
            p.put("lat", s.getLat());
            p.put("lng", s.getLng());
            p.put("minutosDesdeSalida", s.getMinutosDesdeSalida());
            return p;
        }).toList());
        return payload;
    }
}
