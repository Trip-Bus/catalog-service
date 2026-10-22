package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.Bus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface BusRepository extends JpaRepository<Bus, UUID>, JpaSpecificationExecutor<Bus> {

    boolean existsByPlacaAndIdNot(String placa, UUID id);
}
