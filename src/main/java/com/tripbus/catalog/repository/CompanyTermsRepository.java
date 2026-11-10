package com.tripbus.catalog.repository;
import com.tripbus.catalog.domain.CompanyTerms;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface CompanyTermsRepository extends JpaRepository<CompanyTerms, UUID> {
    List<CompanyTerms> findByCompanyIdOrderByVigenteDesdeDesc(UUID companyId);
    Optional<CompanyTerms> findFirstByCompanyIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(UUID companyId, LocalDate fecha);
    boolean existsByCompanyIdAndVigenteDesde(UUID companyId, LocalDate fecha);
}
