package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.PassengerTypeFare;
import com.tripbus.catalog.domain.TipoPasajero;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PassengerTypeFareRepository extends JpaRepository<PassengerTypeFare, UUID> {

    List<PassengerTypeFare> findByCompanyId(UUID companyId);

    Optional<PassengerTypeFare> findByCompanyIdAndTipo(UUID companyId, TipoPasajero tipo);

    /** El descuento de un tipo de pasajero en varias empresas (el precio "desde" de la búsqueda usa el de ADULTO). */
    List<PassengerTypeFare> findByCompanyIdInAndTipo(Collection<UUID> companyIds, TipoPasajero tipo);
}
