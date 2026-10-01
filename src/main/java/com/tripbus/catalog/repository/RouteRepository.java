package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface RouteRepository extends JpaRepository<Route, UUID> {

    @Query("select distinct r.origen from Route r where r.estado = 'ACTIVA'")
    List<String> findOrigenes();

    @Query("select distinct r.destino from Route r where r.estado = 'ACTIVA'")
    List<String> findDestinos();
}
