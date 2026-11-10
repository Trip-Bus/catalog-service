package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.Terminal;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TerminalRepository extends JpaRepository<Terminal, UUID> {

    List<Terminal> findAllByOrderByCiudadAscNombreAsc();

    List<Terminal> findByCiudadNormOrderByNombreAsc(String ciudadNorm);

    boolean existsByCiudadNormAndNombreNormAndIdNot(String ciudadNorm, String nombreNorm, UUID id);

    /** US-115: terminales cuyo nombre o ciudad (normalizados) empiezan por el prefijo. */
    @Query("""
            select t from Terminal t
            where t.nombreNorm like concat(:prefijo, '%') or t.ciudadNorm like concat(:prefijo, '%')
            order by t.ciudad, t.nombre
            """)
    List<Terminal> buscarPorPrefijo(@Param("prefijo") String prefijo, Limit limit);
}
