package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.common.events.EventPublisher;
import com.tripbus.catalog.domain.CompanyTerms;
import com.tripbus.catalog.events.CatalogEvents;
import com.tripbus.catalog.repository.CompanyRepository;
import com.tripbus.catalog.repository.CompanyTermsRepository;
import com.tripbus.catalog.web.dto.CompanyTermsRequest;
import com.tripbus.catalog.web.dto.CompanyTermsResponse;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CompanyTermsService {
    private final CompanyTermsRepository terms;
    private final CompanyRepository companies;
    private final EventPublisher events;
    private final Clock clock;
    private final java.math.BigDecimal defaultCommission;
    private final java.math.BigDecimal defaultMonthlyFee;
    public CompanyTermsService(CompanyTermsRepository terms, CompanyRepository companies, EventPublisher events, Clock clock,
            @Value("${tripbus.catalog.default-commission:5}") java.math.BigDecimal defaultCommission,
            @Value("${tripbus.catalog.default-monthly-fee:0}") java.math.BigDecimal defaultMonthlyFee) {
        this.terms = terms; this.companies = companies; this.events = events; this.clock = clock;
        this.defaultCommission = defaultCommission; this.defaultMonthlyFee = defaultMonthlyFee;
    }
    @Transactional
    public void ensureInitial(UUID companyId) {
        if (!terms.findFirstByCompanyIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(companyId,
                LocalDate.now(clock.withZone(CatalogTime.ZONE))).isPresent())
            terms.save(new CompanyTerms(companyId, defaultMonthlyFee, defaultCommission,
                    LocalDate.now(clock.withZone(CatalogTime.ZONE)), new UUID(0, 0), clock.instant()));
    }
    @Transactional
    public CompanyTermsResponse create(UUID companyId, CompanyTermsRequest request, UUID actor) {
        if (!companies.existsById(companyId)) throw ApiException.notFound("COMPANY_NOT_FOUND", "La empresa no existe");
        LocalDate today = LocalDate.now(clock.withZone(CatalogTime.ZONE));
        if (request.vigenteDesde().isBefore(today)) throw ApiException.badRequest("INVALID_COMPANY_TERMS", "vigenteDesde no puede estar en el pasado");
        if (terms.existsByCompanyIdAndVigenteDesde(companyId, request.vigenteDesde()))
            throw ApiException.conflict("TERMS_ALREADY_EXIST", "Ya existe una vigencia para esa fecha");
        CompanyTerms saved = terms.save(new CompanyTerms(companyId, request.cuotaMensual(), request.comisionPorcentaje(),
                request.vigenteDesde(), actor, clock.instant()));
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("companyId", companyId); payload.put("cuotaMensual", saved.getCuotaMensual());
        payload.put("comisionPorcentaje", saved.getComisionPorcentaje()); payload.put("vigenteDesde", saved.getVigenteDesde());
        events.publishAfterCommit(CatalogEvents.COMPANY_TERMS_CHANGED, payload);
        return CompanyTermsResponse.from(saved);
    }
    @Transactional(readOnly = true)
    public List<CompanyTermsResponse> history(UUID id) {
        if (!companies.existsById(id)) throw ApiException.notFound("COMPANY_NOT_FOUND", "La empresa no existe");
        return terms.findByCompanyIdOrderByVigenteDesdeDesc(id).stream().map(CompanyTermsResponse::from).toList();
    }
    @Transactional(readOnly = true)
    public CompanyTerms current(UUID id, LocalDate date) {
        if (!companies.existsById(id)) throw ApiException.notFound("COMPANY_NOT_FOUND", "La empresa no existe");
        return terms.findFirstByCompanyIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(id, date)
                .orElseThrow(() -> ApiException.notFound("COMPANY_TERMS_NOT_FOUND", "La empresa no tiene condiciones vigentes"));
    }
}
