package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.SeatMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SeatMapRepository extends JpaRepository<SeatMap, UUID> {

    Optional<SeatMap> findByBusId(UUID busId);
}
