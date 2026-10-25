package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.ItineraryChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ItineraryChangeRepository extends JpaRepository<ItineraryChange, UUID> {

    List<ItineraryChange> findByItineraryIdOrderByCreadoEnAsc(UUID itineraryId);
}
