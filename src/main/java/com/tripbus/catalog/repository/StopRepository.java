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

    /** Borrado inmediato (no al final del flush) para poder insertar las nuevas paradas con los mismos órdenes. */
    @Modifying(flushAutomatically = true)
    @Query("delete from Stop s where s.routeId = :routeId")
    void deleteByRouteId(@Param("routeId") UUID routeId);
}
