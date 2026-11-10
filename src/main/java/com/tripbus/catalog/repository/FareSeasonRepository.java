package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.FareSeason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface FareSeasonRepository extends JpaRepository<FareSeason, UUID> {

    List<FareSeason> findByCompanyIdOrderByDesdeAscNombreAsc(UUID companyId);

    List<FareSeason> findAllByOrderByDesdeAscNombreAsc();

    /** Temporadas de la empresa vigentes en la fecha, de la ruta o de toda la empresa (la regla de cuál gana va en Java). */
    @Query("""
            select s from FareSeason s
            where s.companyId = :companyId
              and (s.routeId is null or s.routeId = :routeId)
              and s.desde <= :fecha and s.hasta >= :fecha
            """)
    List<FareSeason> vigentes(@Param("companyId") UUID companyId, @Param("routeId") UUID routeId,
                              @Param("fecha") LocalDate fecha);
}
