package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.CategoriaAsiento;
import com.tripbus.catalog.domain.SeatSurcharge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatSurchargeRepository extends JpaRepository<SeatSurcharge, UUID> {

    List<SeatSurcharge> findByCompanyId(UUID companyId);

    Optional<SeatSurcharge> findByCompanyIdAndCategoria(UUID companyId, CategoriaAsiento categoria);
}
