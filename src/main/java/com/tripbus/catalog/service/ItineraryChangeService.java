package com.tripbus.catalog.service;

import com.tripbus.catalog.client.SeatInventoryClient;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.common.events.EventPublisher;
import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.BusStatus;
import com.tripbus.catalog.domain.ChangeType;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.ItineraryChange;
import com.tripbus.catalog.domain.ItineraryStatus;
import com.tripbus.catalog.events.CatalogEvents;
import com.tripbus.catalog.repository.ItineraryChangeRepository;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.web.dto.ChangeBusRequest;
import com.tripbus.catalog.web.dto.ItineraryChangeResponse;
import com.tripbus.catalog.web.dto.ItineraryDetail;
import com.tripbus.catalog.web.dto.ItinerarySummary;
import com.tripbus.catalog.web.dto.RescheduleRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * US-118/US-119 y cancelación: cambios sobre un viaje ya programado, con su historial (itinerary_changes).
 *
 * <p>Bloqueos: primero la fila del viaje (los cambios de un mismo viaje se serializan) y después la de los
 * buses involucrados, en orden de id. La programación de viajes (US-116) solo bloquea buses, así que no hay
 * ciclos. {@code companyScope} es la empresa del OPERADOR o null para el ADMIN; otra empresa es 404.
 */
@Service
public class ItineraryChangeService {

    private static final Logger log = LoggerFactory.getLogger(ItineraryChangeService.class);
    static final String ITINERARY_CANCELLED = "ITINERARY_CANCELLED";
    static final String MSG_CANCELADO = "El viaje está cancelado";
    static final String MSG_TERMINADO = "El viaje ya terminó";
    private static final String ITINERARY_ID = "itineraryId";
    private static final String COMPANY_ID = "companyId";

    private final ItineraryRepository itineraryRepository;
    private final ItineraryChangeRepository changeRepository;
    private final BusSchedule busSchedule;
    private final ItineraryPublisher itineraryPublisher;
    private final SeatInventoryClient seatInventoryClient;
    private final ItineraryService itineraryService;
    private final EventPublisher eventPublisher;
    private final SearchCache searchCache;
    private final Clock clock;
    private final Duration maxDelay;

    @SuppressWarnings("java:S107") // Orquesta repositorios, Seat Inventory, eventos y cache: todos son necesarios.
    public ItineraryChangeService(
            ItineraryRepository itineraryRepository,
            ItineraryChangeRepository changeRepository,
            BusSchedule busSchedule,
            ItineraryPublisher itineraryPublisher,
            SeatInventoryClient seatInventoryClient,
            ItineraryService itineraryService,
            EventPublisher eventPublisher,
            SearchCache searchCache,
            Clock clock,
            @Value("${tripbus.catalog.max-delay:6h}") Duration maxDelay) {
        this.itineraryRepository = itineraryRepository;
        this.changeRepository = changeRepository;
        this.busSchedule = busSchedule;
        this.itineraryPublisher = itineraryPublisher;
        this.seatInventoryClient = seatInventoryClient;
        this.itineraryService = itineraryService;
        this.eventPublisher = eventPublisher;
        this.searchCache = searchCache;
        this.clock = clock;
        this.maxDelay = maxDelay;
    }

    /**
     * US-118: nueva salida (la llegada se recalcula con la duración de la ruta). Solo antes de la salida
     * original y si no está cancelado. RETRASO si se corre hacia adelante hasta tripbus.catalog.max-delay;
     * REPROGRAMACION en cualquier otro caso (más tarde que eso, o adelantar el viaje).
     */
    @Transactional
    public ItinerarySummary reschedule(UUID id, RescheduleRequest request, UUID companyScope, UUID hechoPor) {
        Itinerary itinerary = bloquear(id, companyScope);
        Instant ahora = clock.instant();
        if (itinerary.getEstado() == ItineraryStatus.CANCELADO) {
            throw ApiException.conflict(ITINERARY_CANCELLED, MSG_CANCELADO);
        }
        if (!itinerary.getFechaSalida().isAfter(ahora)) {
            throw ApiException.conflict("ITINERARY_ALREADY_DEPARTED", "El viaje ya salió y no se puede reprogramar");
        }
        Instant nueva = request.nuevaFechaSalida();
        if (!nueva.isAfter(ahora)) {
            throw ApiException.badRequest("DEPARTURE_IN_PAST", "La nueva salida debe estar en el futuro");
        }
        Instant anterior = itinerary.getFechaSalida();
        if (nueva.equals(anterior)) {
            throw ApiException.badRequest("SAME_DEPARTURE", "La nueva salida es igual a la actual");
        }
        Instant llegada = ItineraryPlanningService.llegada(itinerary.getRoute(), nueva);
        UUID busId = itinerary.getBus().getId();
        busSchedule.lock(busId);
        busSchedule.conflictExcept(busId, nueva, llegada, itinerary.getId()).ifPresent(choque -> {
            throw BusSchedule.conflictError(choque);
        });

        Duration diferencia = Duration.between(anterior, nueva);
        ChangeType tipo = tipo(diferencia, maxDelay);
        itinerary.reprogramar(nueva, llegada);
        changeRepository.save(new ItineraryChange(itinerary.getId(), tipo, anterior, nueva, null, null,
                request.motivo(), hechoPor, ahora));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ITINERARY_ID, itinerary.getId());
        payload.put(COMPANY_ID, itinerary.getRoute().getCompany().getId());
        payload.put("routeId", itinerary.getRoute().getId());
        payload.put("busId", busId);
        payload.put("fechaSalidaAnterior", anterior);
        payload.put("fechaSalida", nueva);
        payload.put("fechaLlegada", llegada);
        payload.put("minutosDiferencia", diferencia.toMinutes());
        payload.put("tipo", tipo.name());
        payload.put("motivo", request.motivo());
        eventPublisher.publishAfterCommit(CatalogEvents.ITINERARY_RESCHEDULED, payload);
        searchCache.evictAll();
        return ItinerarySummary.from(itinerary);
    }

    /** RETRASO si se corre hacia adelante como máximo {@code maxDelay}; si no, REPROGRAMACION. */
    static ChangeType tipo(Duration diferencia, Duration maxDelay) {
        return !diferencia.isNegative() && diferencia.compareTo(maxDelay) <= 0
                ? ChangeType.RETRASO
                : ChangeType.REPROGRAMACION;
    }

    /**
     * US-119: el viaje pasa a otro bus de la misma empresa, ACTIVO y sin cruces. Antes de guardar, Seat
     * Inventory reasigna los asientos al mapa del bus nuevo; si después la transacción de Catalog falla, se
     * compensa reasignando los del bus anterior.
     */
    @Transactional
    public ItinerarySummary changeBus(UUID id, ChangeBusRequest request, UUID companyScope, UUID hechoPor) {
        Itinerary itinerary = bloquear(id, companyScope);
        validarNoCanceladoNiTerminado(itinerary);
        Bus anterior = itinerary.getBus();
        if (request.busId().equals(anterior.getId())) {
            throw ApiException.badRequest("SAME_BUS", "El viaje ya tiene ese bus");
        }
        Bus nuevo = bloquearBuses(anterior.getId(), request.busId()).get(request.busId());
        if (nuevo == null || (companyScope != null && !companyScope.equals(nuevo.getCompanyId()))) {
            throw ApiException.notFound("BUS_NOT_FOUND", "El bus no existe");
        }
        if (!nuevo.getCompanyId().equals(itinerary.getRoute().getCompany().getId())) {
            throw ApiException.badRequest("COMPANY_MISMATCH", "El bus nuevo debe ser de la empresa del viaje");
        }
        if (nuevo.getEstado() != BusStatus.ACTIVO) {
            throw ApiException.conflict("BUS_NOT_ACTIVE", "El bus no está ACTIVO (" + nuevo.getEstado() + ")");
        }
        busSchedule.conflictExcept(nuevo.getId(), itinerary.getFechaSalida(), itinerary.getFechaLlegadaEstimada(),
                itinerary.getId()).ifPresent(choque -> {
                    throw BusSchedule.conflictError(choque);
                });

        List<String> asientosNuevos = itineraryPublisher.seatNumbers(nuevo);
        List<String> asientosAnteriores = itineraryPublisher.seatNumbers(anterior);
        reasignarAsientos(itinerary.getId(), asientosNuevos);
        compensarSiFalla(itinerary.getId(), asientosAnteriores);

        itinerary.cambiarBus(nuevo);
        changeRepository.save(new ItineraryChange(itinerary.getId(), ChangeType.CAMBIO_BUS, null, null,
                anterior.getId(), nuevo.getId(), request.motivo(), hechoPor, clock.instant()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ITINERARY_ID, itinerary.getId());
        payload.put(COMPANY_ID, itinerary.getRoute().getCompany().getId());
        payload.put("busAnterior", anterior.getId());
        payload.put("busNuevo", nuevo.getId());
        payload.put("capacidad", asientosNuevos.size());
        payload.put("seatNumbers", asientosNuevos);
        eventPublisher.publishAfterCommit(CatalogEvents.ITINERARY_BUS_CHANGED, payload);
        searchCache.evictAll();
        return ItinerarySummary.from(itinerary);
    }

    /** POST /admin/itineraries/{id}/cancel. Booking consume ItineraryCancelled. */
    @Transactional
    public ItineraryDetail cancel(UUID id, String motivo, UUID companyScope, UUID hechoPor) {
        Itinerary itinerary = bloquear(id, companyScope);
        if (itinerary.getEstado() == ItineraryStatus.CANCELADO) {
            throw ApiException.conflict("ITINERARY_ALREADY_CANCELLED", "El itinerario ya está cancelado");
        }
        if (!itinerary.getFechaLlegadaEstimada().isAfter(clock.instant())) {
            throw ApiException.conflict("ITINERARY_FINISHED", MSG_TERMINADO + " y no se puede cancelar");
        }
        itinerary.cancelar();
        changeRepository.save(new ItineraryChange(itinerary.getId(), ChangeType.CANCELACION,
                itinerary.getFechaSalida(), null, null, null, motivo, hechoPor, clock.instant()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ITINERARY_ID, itinerary.getId());
        payload.put(COMPANY_ID, itinerary.getRoute().getCompany().getId());
        payload.put("routeId", itinerary.getRoute().getId());
        payload.put("fechaSalida", itinerary.getFechaSalida());
        payload.put("motivo", motivo);
        eventPublisher.publishAfterCommit(CatalogEvents.ITINERARY_CANCELLED, payload);
        searchCache.evictAll();
        return itineraryService.detail(id);
    }

    @Transactional(readOnly = true)
    public List<ItineraryChangeResponse> changes(UUID id, UUID companyScope) {
        itineraryRepository.findDetailed(id)
                .filter(i -> perteneceA(i, companyScope))
                .orElseThrow(ItineraryChangeService::noExiste);
        return changeRepository.findByItineraryIdOrderByCreadoEnAsc(id).stream()
                .map(ItineraryChangeResponse::from)
                .toList();
    }

    private Itinerary bloquear(UUID id, UUID companyScope) {
        return itineraryRepository.findByIdForUpdate(id)
                .filter(i -> perteneceA(i, companyScope))
                .orElseThrow(ItineraryChangeService::noExiste);
    }

    private static boolean perteneceA(Itinerary i, UUID companyScope) {
        return companyScope == null || companyScope.equals(i.getRoute().getCompany().getId());
    }

    private static ApiException noExiste() {
        return ApiException.notFound("ITINERARY_NOT_FOUND", "El itinerario no existe");
    }

    private void validarNoCanceladoNiTerminado(Itinerary itinerary) {
        if (itinerary.getEstado() == ItineraryStatus.CANCELADO) {
            throw ApiException.conflict(ITINERARY_CANCELLED, MSG_CANCELADO);
        }
        if (!itinerary.getFechaLlegadaEstimada().isAfter(clock.instant())) {
            throw ApiException.conflict("ITINERARY_FINISHED", MSG_TERMINADO);
        }
    }

    /** SELECT ... FOR UPDATE de los dos buses, siempre en orden de id para que no haya interbloqueos. */
    private Map<UUID, Bus> bloquearBuses(UUID a, UUID b) {
        Map<UUID, Bus> buses = new HashMap<>();
        for (UUID id : a.compareTo(b) < 0 ? List.of(a, b) : List.of(b, a)) {
            busSchedule.lock(id).ifPresent(bus -> buses.put(id, bus));
        }
        return buses;
    }

    private void reasignarAsientos(UUID itineraryId, List<String> asientos) {
        SeatInventoryClient.RemapResult r = seatInventoryClient.remap(itineraryId, asientos, asientos.size());
        switch (r.status()) {
            case SEATS_CONFLICT -> throw new ApiException(HttpStatus.CONFLICT, "SEATS_CONFLICT",
                    "Hay asientos vendidos o retenidos que no existen en el bus nuevo",
                    Map.of("asientos", r.asientosEnConflicto()));
            case UNAVAILABLE -> throw ApiException.unavailable("SEAT_INVENTORY_UNAVAILABLE",
                    "Seat Inventory no respondió; no se cambió el bus");
            default -> log.debug("Asientos del viaje {} reasignados en Seat Inventory", itineraryId);
        }
    }

    /** Si la transacción de Catalog no llega a confirmarse, devuelve en Seat Inventory los asientos del bus anterior. */
    private void compensarSiFalla(UUID itineraryId, List<String> asientosAnteriores) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                log.error("El cambio de bus del viaje {} no se guardó después de reasignar sus asientos en Seat "
                        + "Inventory; se compensa con los asientos del bus anterior", itineraryId);
                SeatInventoryClient.RemapResult r =
                        seatInventoryClient.remap(itineraryId, asientosAnteriores, asientosAnteriores.size());
                if (r.status() != SeatInventoryClient.RemapResult.Status.OK) {
                    log.error("No se pudo compensar en Seat Inventory el viaje {} ({}): quedó con los asientos del "
                            + "bus nuevo y hay que corregirlo a mano", itineraryId, r.status());
                }
            }
        });
    }
}
