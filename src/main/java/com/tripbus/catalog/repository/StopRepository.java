package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.Stop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StopRepository extends JpaRepository<Stop, UUID> {

    List<Stop> findByRouteIdOrderByOrdenAsc(UUID routeId);
}
