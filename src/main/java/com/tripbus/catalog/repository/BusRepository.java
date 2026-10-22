package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.Bus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BusRepository extends JpaRepository<Bus, UUID>, JpaSpecificationExecutor<Bus> {

    boolean existsByPlacaAndIdNot(String placa, UUID id);

    /**
     * US-117: SELECT ... FOR UPDATE sobre la fila del bus. Serializa la programación de viajes de un
     * mismo bus para que dos solicitudes simultáneas no creen viajes cruzados.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bus b where b.id = :id")
    Optional<Bus> findByIdForUpdate(@Param("id") UUID id);
}
