package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.Itinerary;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ItineraryRepository extends JpaRepository<Itinerary, UUID> {

    /** Búsqueda principal (US-007): ordenada por hora de salida. */
    @Query("""
            select i from Itinerary i
              join fetch i.route r
              join fetch r.company
              join fetch i.bus
            where r.origenNorm = :origen
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
     * uno viene lleno). Ordenados por hora de salida.
     */
    @Query("""
            select i from Itinerary i
              join fetch i.route r
              join fetch r.company
              join fetch i.bus
            where (:origen = '' or r.origenNorm = :origen)
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
    java.util.Optional<Itinerary> findDetailed(@Param("id") UUID id);

    List<Itinerary> findByOfertaId(UUID ofertaId);
}
