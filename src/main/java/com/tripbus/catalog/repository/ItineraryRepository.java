package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.Itinerary;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ItineraryRepository extends JpaRepository<Itinerary, UUID>, JpaSpecificationExecutor<Itinerary> {

    /** Búsqueda principal (US-007): ordenada por hora de salida. Solo empresas ACTIVA (US-100). */
    @Query("""
            select i from Itinerary i
              join fetch i.route r
              join fetch r.company c
              join fetch i.bus
            where r.origenNorm = :origen
              and c.estado = com.tripbus.catalog.domain.CompanyStatus.ACTIVA
              and r.destinoNorm = :destino
              and i.estado = com.tripbus.catalog.domain.ItineraryStatus.PROGRAMADO
              and i.fechaSalida >= :desde
              and i.fechaSalida < :hasta
            order by i.fechaSalida asc
            """)
    List<Itinerary> search(
            @Param("origen") String origen,
            @Param("destino") String destino,
            @Param("desde") Instant desde,
            @Param("hasta") Instant hasta);

    /**
     * Viajes en ruta (US-039/US-047). origen/destino ya normalizados; vacío = sin filtro (al menos
     * uno viene lleno). Ordenados por hora de salida. Solo empresas ACTIVA (US-100).
     */
    @Query("""
            select i from Itinerary i
              join fetch i.route r
              join fetch r.company c
              join fetch i.bus
            where (:origen = '' or r.origenNorm = :origen)
              and c.estado = com.tripbus.catalog.domain.CompanyStatus.ACTIVA
              and (:destino = '' or r.destinoNorm = :destino)
              and i.estado <> com.tripbus.catalog.domain.ItineraryStatus.CANCELADO
              and i.fechaSalida <= :saleAntesDe
              and i.fechaLlegadaEstimada >= :llegaDespuesDe
            order by i.fechaSalida asc
            """)
    List<Itinerary> findInProgress(
            @Param("origen") String origen,
            @Param("destino") String destino,
            @Param("saleAntesDe") Instant saleAntesDe,
            @Param("llegaDespuesDe") Instant llegaDespuesDe,
            Limit limit);

    @Query("""
            select i from Itinerary i
              join fetch i.route r
              join fetch r.company
              join fetch i.bus
            where i.id = :id
            """)
    Optional<Itinerary> findDetailed(@Param("id") UUID id);

    List<Itinerary> findByOfertaId(UUID ofertaId);

    /** US-111: el bus tiene viajes que aún no salen y no están cancelados (BUS_IN_USE). */
    @Query("""
            select count(i) > 0 from Itinerary i
            where i.bus.id = :busId
              and i.estado <> com.tripbus.catalog.domain.ItineraryStatus.CANCELADO
              and i.fechaSalida > :ahora
            """)
    boolean existsFutureForBus(@Param("busId") UUID busId, @Param("ahora") Instant ahora);

    /** US-114: la ruta tiene viajes que aún no salen y no están cancelados (ROUTE_IN_USE). */
    @Query("""
            select count(i) > 0 from Itinerary i
            where i.route.id = :routeId
              and i.estado <> com.tripbus.catalog.domain.ItineraryStatus.CANCELADO
              and i.fechaSalida > :ahora
            """)
    boolean existsFutureForRoute(@Param("routeId") UUID routeId, @Param("ahora") Instant ahora);

    /**
     * US-117: viajes no cancelados del bus que se cruzan con [desde, hasta). El llamador ya amplió el
     * intervalo con el margen de alistamiento. Ordenados por salida (el primero es el que se informa).
     */
    @Query("""
            select i.id from Itinerary i
            where i.bus.id = :busId
              and i.estado <> com.tripbus.catalog.domain.ItineraryStatus.CANCELADO
              and i.fechaSalida < :hasta
              and i.fechaLlegadaEstimada > :desde
            order by i.fechaSalida asc
            """)
    List<UUID> findOverlapping(
            @Param("busId") UUID busId,
            @Param("desde") Instant desde,
            @Param("hasta") Instant hasta,
            Limit limit);

    /** US-118/US-119: igual que findOverlapping pero sin contar el propio viaje. */
    @Query("""
            select i.id from Itinerary i
            where i.bus.id = :busId
              and i.id <> :excluir
              and i.estado <> com.tripbus.catalog.domain.ItineraryStatus.CANCELADO
              and i.fechaSalida < :hasta
              and i.fechaLlegadaEstimada > :desde
            order by i.fechaSalida asc
            """)
    List<UUID> findOverlappingExcept(
            @Param("busId") UUID busId,
            @Param("desde") Instant desde,
            @Param("hasta") Instant hasta,
            @Param("excluir") UUID excluir,
            Limit limit);

    /**
     * SELECT ... FOR UPDATE solo sobre la fila del viaje (sin joins, para no bloquear también su bus): los
     * cambios de un mismo viaje (reprogramar, cambiar bus, cancelar) se serializan. Siempre se toma antes
     * que el bloqueo de los buses, así que no hay ciclos con la programación de viajes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Itinerary i where i.id = :id")
    Optional<Itinerary> findByIdForUpdate(@Param("id") UUID id);

    /** GET /itineraries (US-116): página con ruta, empresa y bus en la misma consulta. */
    @Override
    @EntityGraph(attributePaths = {"route", "route.company", "bus"})
    Page<Itinerary> findAll(Specification<Itinerary> spec, Pageable pageable);
}
