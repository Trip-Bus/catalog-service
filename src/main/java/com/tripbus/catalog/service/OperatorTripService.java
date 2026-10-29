package com.tripbus.catalog.service;

import com.tripbus.catalog.client.SeatInventoryClient;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.repository.CompanyRepository;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.web.dto.OfertaResumen;
import com.tripbus.catalog.web.dto.OperatorTripPage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OperatorTripService {

    private static final BigDecimal LOW_OCCUPANCY = new BigDecimal("40");
    private final ItineraryRepository itineraryRepository;
    private final CompanyRepository companyRepository;
    private final SeatInventoryClient seatInventoryClient;
    private final Clock clock;

    public OperatorTripService(ItineraryRepository itineraryRepository, CompanyRepository companyRepository,
                               SeatInventoryClient seatInventoryClient, Clock clock) {
        this.itineraryRepository = itineraryRepository;
        this.companyRepository = companyRepository;
        this.seatInventoryClient = seatInventoryClient;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OperatorTripPage list(LocalDate desde, LocalDate hasta, UUID requestedCompany, UUID companyScope,
                                 int page, int size) {
        LocalDate today = LocalDate.now(clock.withZone(CatalogTime.ZONE));
        LocalDate start = desde == null ? today : desde;
        LocalDate end = hasta == null ? start.plusDays(7) : hasta;
        validateRange(start, end, page, size);

        UUID companyId = resolveCompany(requestedCompany, companyScope);
        if (!companyRepository.existsById(companyId)) {
            throw ApiException.notFound("COMPANY_NOT_FOUND", "La empresa no existe");
        }

        Instant from = start.atStartOfDay(CatalogTime.ZONE).toInstant();
        Instant to = end.plusDays(1).atStartOfDay(CatalogTime.ZONE).toInstant();
        Specification<Itinerary> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("route").get("company").get("id"), companyId),
                cb.greaterThanOrEqualTo(root.get("fechaSalida"), from),
                cb.lessThan(root.get("fechaSalida"), to));
        Sort sort = Sort.by(Sort.Direction.ASC, "fechaSalida").and(Sort.by("id"));
        Page<Itinerary> trips = itineraryRepository.findAll(spec, PageRequest.of(page, size, sort));

        List<UUID> itineraryIds = trips.getContent().stream().map(Itinerary::getId).toList();
        Optional<Map<UUID, SeatInventoryClient.Occupancy>> occupancy = seatInventoryClient.occupancy(itineraryIds);
        Instant now = clock.instant();
        List<OperatorTripPage.Trip> content = trips.getContent().stream()
                .map(itinerary -> toTrip(itinerary, occupancy, now))
                .toList();
        return new OperatorTripPage(summary(trips, content, now), content, page, size,
                trips.getTotalElements(), trips.getTotalPages());
    }

    private static void validateRange(LocalDate start, LocalDate end, int page, int size) {
        if (start.isAfter(end) || end.isAfter(start.plusDays(30)) || page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_SEARCH", "Rango o página inválidos (máximo 31 días)");
        }
    }

    private static UUID resolveCompany(UUID requestedCompany, UUID companyScope) {
        UUID companyId = companyScope == null ? requestedCompany : companyScope;
        if (companyId == null) {
            throw ApiException.badRequest("COMPANY_REQUIRED", "El administrador debe indicar companyId");
        }
        return companyId;
    }

    private static OperatorTripPage.Trip toTrip(Itinerary itinerary,
            Optional<Map<UUID, SeatInventoryClient.Occupancy>> occupancy, Instant now) {
        SeatInventoryClient.Occupancy value = occupancy.map(map -> map.get(itinerary.getId())).orElse(null);
        OperatorTripPage.Occupancy dto = value == null ? null : new OperatorTripPage.Occupancy(value.vendidos(),
                value.disponibles(), value.bloqueados(), value.porcentaje(), value.tasaAbandono());
        OfertaResumen offer = itinerary.tieneOfertaActiva(now)
                ? new OfertaResumen(itinerary.getOfertaId(), itinerary.getPrecioOferta(), itinerary.getOfertaFin())
                : null;
        return new OperatorTripPage.Trip(itinerary.getId(), itinerary.getRoute().getOrigen(),
                itinerary.getRoute().getDestino(), itinerary.getFechaSalida(), itinerary.getFechaLlegadaEstimada(),
                itinerary.getBus().getPlaca(), itinerary.getBus().getTipo().name(), itinerary.getEstado().name(),
                itinerary.getPrecioBase(), offer, dto, value != null);
    }

    private static OperatorTripPage.Summary summary(Page<Itinerary> trips, List<OperatorTripPage.Trip> content,
                                                     Instant now) {
        List<BigDecimal> knownPercentages = content.stream()
                .filter(OperatorTripPage.Trip::ocupacionConocida)
                .map(trip -> trip.ocupacion().porcentaje())
                .toList();
        BigDecimal average = knownPercentages.isEmpty() ? null : knownPercentages.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(knownPercentages.size()), 2, RoundingMode.HALF_UP);
        Instant nextDay = now.plus(Duration.ofHours(24));
        long offers = content.stream()
                .filter(trip -> !trip.fechaSalida().isBefore(now) && !trip.fechaSalida().isAfter(nextDay))
                .filter(trip -> trip.ocupacion() != null
                        && trip.ocupacion().porcentaje().compareTo(LOW_OCCUPANCY) < 0)
                .count();
        return new OperatorTripPage.Summary(trips.getTotalElements(), average, offers);
    }
}
