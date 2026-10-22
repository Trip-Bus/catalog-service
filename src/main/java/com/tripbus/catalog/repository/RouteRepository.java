package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.Route;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RouteRepository extends JpaRepository<Route, UUID>, JpaSpecificationExecutor<Route> {

    @Query("select distinct r.origen from Route r where r.estado = com.tripbus.catalog.domain.RouteStatus.ACTIVA")
    List<String> findOrigenes();

    @Query("select distinct r.destino from Route r where r.estado = com.tripbus.catalog.domain.RouteStatus.ACTIVA")
    List<String> findDestinos();

    @EntityGraph(attributePaths = "company")
    @Query("select r from Route r where r.id = :id")
    Optional<Route> findWithCompany(@Param("id") UUID id);
}
