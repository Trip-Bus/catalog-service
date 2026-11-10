package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.SegmentPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SegmentPriceRepository extends JpaRepository<SegmentPrice, UUID> {

    List<SegmentPrice> findByRouteIdOrderByDesdeOrdenAscHastaOrdenAsc(UUID routeId);

    List<SegmentPrice> findByRouteIdIn(Collection<UUID> routeIds);

    Optional<SegmentPrice> findByRouteIdAndDesdeOrdenAndHastaOrden(UUID routeId, int desdeOrden, int hastaOrden);

    @Modifying(flushAutomatically = true)
    @Query("delete from SegmentPrice p where p.routeId = :routeId")
    void deleteByRouteId(@Param("routeId") UUID routeId);
}
