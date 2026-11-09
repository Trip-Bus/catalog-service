package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.ItineraryStatus;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.web.dto.InternalItinerary;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * GET /internal/itineraries (servicio a servicio; lo usan flash-offers y booking): viajes no cancelados que salen
 * entre desde y hasta (fechas de Colombia, ambas incluidas), opcionalmente de una empresa y una ruta. Máximo 31
 * días y 1.000 resultados, ordenados por salida.
 */
@Service
public class InternalItineraryService {

    static final int MAX_DIAS = 31;
    static final int MAX_RESULTADOS = 1000;

    private final ItineraryRepository itineraryRepository;

    public InternalItineraryService(ItineraryRepository itineraryRepository) {
        this.itineraryRepository = itineraryRepository;
    }

    @Transactional(readOnly = true)
    public List<InternalItinerary> list(UUID companyId, LocalDate desde, LocalDate hasta, UUID routeId) {
        if (hasta.isBefore(desde) || ChronoUnit.DAYS.between(desde, hasta) >= MAX_DIAS) {
            throw ApiException.badRequest("INVALID_RANGE",
                    "hasta no puede ser anterior a desde y el rango es de máximo " + MAX_DIAS + " días");
        }
        Instant inicio = desde.atStartOfDay(CatalogTime.ZONE).toInstant();
        Instant fin = hasta.plusDays(1).atStartOfDay(CatalogTime.ZONE).toInstant();
        Specification<Itinerary> spec = (root, query, cb) -> {
            List<Predicate> filtros = new ArrayList<>();
            filtros.add(cb.notEqual(root.get("estado"), ItineraryStatus.CANCELADO));
            filtros.add(cb.greaterThanOrEqualTo(root.get("fechaSalida"), inicio));
            filtros.add(cb.lessThan(root.get("fechaSalida"), fin));
            if (companyId != null) {
                filtros.add(cb.equal(root.get("route").get("company").get("id"), companyId));
            }
            if (routeId != null) {
                filtros.add(cb.equal(root.get("route").get("id"), routeId));
            }
            return cb.and(filtros.toArray(new Predicate[0]));
        };
        return itineraryRepository.findAll(spec, PageRequest.of(0, MAX_RESULTADOS, Sort.by("fechaSalida", "id")))
                .map(InternalItinerary::from).getContent();
    }
}
