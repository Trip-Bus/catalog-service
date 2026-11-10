package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.Stop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StopRepository extends JpaRepository<Stop, UUID> {

    List<Stop> findByRouteIdOrderByOrdenAsc(UUID routeId);

    /** US-115: alguna parada usa la terminal (no se puede borrar). */
    boolean existsByTerminalId(UUID terminalId);

    /**
     * US-138: tramos de las rutas ACTIVA en los que origen y destino (normalizados) son paradas, la de origen
     * antes que la de destino. Incluye los recorridos completos (el llamador decide).
     */
    @Query("""
            select new com.tripbus.catalog.repository.TramoParada(o.routeId, o.orden, d.orden,
                   o.minutosDesdeSalida, d.minutosDesdeSalida, r.duracionMinutos)
            from Stop o, Stop d, Route r
            where o.routeId = d.routeId
              and r.id = o.routeId
              and r.estado = com.tripbus.catalog.domain.RouteStatus.ACTIVA
              and o.nombreNorm = :origen
              and d.nombreNorm = :destino
              and o.orden < d.orden
            """)
    List<TramoParada> tramos(@Param("origen") String origen, @Param("destino") String destino);

    /** Borrado inmediato (no al final del flush) para poder insertar las nuevas paradas con los mismos órdenes. */
    @Modifying(flushAutomatically = true)
    @Query("delete from Stop s where s.routeId = :routeId")
    void deleteByRouteId(@Param("routeId") UUID routeId);
}
