package com.tripbus.catalog.web;

import com.tripbus.catalog.service.CompanyService;
import com.tripbus.catalog.web.dto.CompanyInternalResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Servicio a servicio (X-Internal-Token, ver InternalTokenFilter): identity valida el companyId
 * de un operador. El API Gateway nunca enruta /internal/**.
 */
@RestController
@RequestMapping("/internal/companies")
public class InternalCompanyController {

    private final CompanyService companyService;

    public InternalCompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @GetMapping("/{id}")
    public CompanyInternalResponse get(@PathVariable UUID id, @RequestParam(required = false) LocalDate fecha) {
        return companyService.internal(id, fecha);
    }
}
