package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.repository.BusRepository;
import com.tripbus.catalog.repository.ItineraryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * US-117: un bus no puede estar en dos viajes no cancelados que se crucen en [salida, llegada + alistamiento]
 * (tripbus.catalog.bus-turnaround). Antes de revisar el cruce, el llamador bloquea la fila del bus con
 * {@link #lock(UUID)} dentro de su transacción.
 */
@Component
public class BusSchedule {

    public static final String CONFLICT = "BUS_SCHEDULE_CONFLICT";
    public static final String CONFLICT_MESSAGE = "El bus ya tiene un viaje que se cruza con este horario";

    private final BusRepository busRepository;
    private final ItineraryRepository itineraryRepository;
    private final Duration turnaround;

    public BusSchedule(
            BusRepository busRepository,
            ItineraryRepository itineraryRepository,
            @Value("${tripbus.catalog.bus-turnaround:30m}") Duration turnaround) {
        this.busRepository = busRepository;
        this.itineraryRepository = itineraryRepository;
        this.turnaround = turnaround;
    }

    /** SELECT ... FOR UPDATE de la fila del bus hasta el fin de la transacción. */
    public Optional<Bus> lock(UUID busId) {
        return busRepository.findByIdForUpdate(busId);
    }

    /** Primer viaje del bus que choca con [salida, llegada] más el alistamiento a cada lado. */
    public Optional<UUID> findConflict(UUID busId, Instant salida, Instant llegada) {
        return first(itineraryRepository.findOverlapping(busId, salida.minus(turnaround), llegada.plus(turnaround),
                Limit.of(1)));
    }

    /** Igual que {@link #findConflict} sin contar el viaje {@code excluir} (al reprogramarlo o cambiarle el bus). */
    public Optional<UUID> findConflictExcept(UUID busId, Instant salida, Instant llegada, UUID excluir) {
        return first(itineraryRepository.findOverlappingExcept(busId, salida.minus(turnaround),
                llegada.plus(turnaround), excluir, Limit.of(1)));
    }

    /** 409 BUS_SCHEDULE_CONFLICT con el viaje con el que choca (details.itineraryId). */
    public static ApiException conflictError(UUID itineraryId) {
        return new ApiException(HttpStatus.CONFLICT, CONFLICT, CONFLICT_MESSAGE, Map.of("itineraryId", itineraryId));
    }

    /** 409 BUS_SCHEDULE_CONFLICT sin viaje conocido (lo rechazó la restricción EXCLUDE de la base). */
    public static ApiException conflictError() {
        return new ApiException(HttpStatus.CONFLICT, CONFLICT, CONFLICT_MESSAGE, Map.of());
    }

    private static Optional<UUID> first(List<UUID> ids) {
        return ids.stream().findFirst();
    }
}
