package com.tripbus.catalog.repository;

import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.CompanyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface CompanyRepository extends JpaRepository<Company, UUID> {

    Page<Company> findByEstado(CompanyStatus estado, Pageable pageable);

    /** NIT ya usado por otra empresa, sin importar el dígito de verificación (índice uq_companies_nit). */
    @Query(value = "select exists(select 1 from companies where split_part(nit, '-', 1) = :base and id <> :id)",
            nativeQuery = true)
    boolean existsNitBase(@Param("base") String base, @Param("id") UUID excludeId);
}
