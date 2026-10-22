package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.common.events.EventPublisher;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.CompanyStatus;
import com.tripbus.catalog.events.CatalogEvents;
import com.tripbus.catalog.repository.CompanyRepository;
import com.tripbus.catalog.web.dto.CompanyInternalResponse;
import com.tripbus.catalog.web.dto.CompanyRequest;
import com.tripbus.catalog.web.dto.CompanyResponse;
import com.tripbus.catalog.web.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * US-100: empresas transportadoras. Una empresa INACTIVA no aparece en /trips/search ni en
 * /trips/in-progress y no puede crear buses.
 */
@Service
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final EventPublisher eventPublisher;
    private final SearchCache searchCache;
    private final Clock clock;

    public CompanyService(
            CompanyRepository companyRepository,
            EventPublisher eventPublisher,
            SearchCache searchCache,
            Clock clock) {
        this.companyRepository = companyRepository;
        this.eventPublisher = eventPublisher;
        this.searchCache = searchCache;
        this.clock = clock;
    }

    @Transactional
    public CompanyResponse create(CompanyRequest request) {
        UUID id = UUID.randomUUID();
        validarNitLibre(request.nit(), id);
        Company company = companyRepository.save(new Company(id, request.nombre().trim(), request.nit()));
        eventPublisher.publishAfterCommit(CatalogEvents.COMPANY_CREATED, payload(company));
        return CompanyResponse.from(company);
    }

    @Transactional(readOnly = true)
    public PageResponse<CompanyResponse> list(CompanyStatus estado, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("nombre", "id"));
        Page<Company> result = estado == null
                ? companyRepository.findAll(pageable)
                : companyRepository.findByEstado(estado, pageable);
        return PageResponse.of(result, CompanyResponse::from);
    }

    @Transactional(readOnly = true)
    public CompanyResponse get(UUID id) {
        return CompanyResponse.from(find(id));
    }

    @Transactional
    public CompanyResponse update(UUID id, CompanyRequest request) {
        Company company = find(id);
        validarNitLibre(request.nit(), id);
        company.actualizar(request.nombre().trim(), request.nit(), clock.instant());
        eventPublisher.publishAfterCommit(CatalogEvents.COMPANY_UPDATED, payload(company));
        // El nombre de la empresa sale en los resultados de búsqueda cacheados.
        searchCache.evictAll();
        return CompanyResponse.from(company);
    }

    /** ACTIVA ↔ INACTIVA. Si el estado no cambia no hace nada (ni evento ni invalidación de la cache). */
    @Transactional
    public CompanyResponse changeStatus(UUID id, CompanyStatus estado) {
        Company company = find(id);
        if (company.getEstado() != estado) {
            company.cambiarEstado(estado, clock.instant());
            eventPublisher.publishAfterCommit(estado == CompanyStatus.INACTIVA
                    ? CatalogEvents.COMPANY_DEACTIVATED
                    : CatalogEvents.COMPANY_UPDATED, payload(company));
            searchCache.evictAll();
        }
        return CompanyResponse.from(company);
    }

    @Transactional(readOnly = true)
    public CompanyInternalResponse internal(UUID id) {
        Company company = find(id);
        return new CompanyInternalResponse(company.getId(), company.getNombre(), company.getEstado().name());
    }

    /** Empresa existente y ACTIVA (para crear buses). */
    @Transactional(readOnly = true)
    public Company requireActive(UUID id) {
        Company company = find(id);
        if (!company.isActiva()) {
            throw ApiException.conflict("COMPANY_INACTIVE", "La empresa está inactiva y no puede crear buses");
        }
        return company;
    }

    private Company find(UUID id) {
        return companyRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("COMPANY_NOT_FOUND", "La empresa no existe"));
    }

    private void validarNitLibre(String nit, UUID id) {
        if (companyRepository.existsNitBase(nitBase(nit), id)) {
            throw ApiException.conflict("NIT_ALREADY_EXISTS", "Ya existe una empresa con el NIT " + nit);
        }
    }

    /** NIT sin el dígito de verificación: 900123456-7 → 900123456 (el índice único compara esto). */
    static String nitBase(String nit) {
        int guion = nit.indexOf('-');
        return guion < 0 ? nit : nit.substring(0, guion);
    }

    private static Map<String, Object> payload(Company c) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("companyId", c.getId());
        payload.put("nombre", c.getNombre());
        payload.put("nit", c.getNit());
        payload.put("estado", c.getEstado().name());
        return payload;
    }
}
