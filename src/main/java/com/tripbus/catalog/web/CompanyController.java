package com.tripbus.catalog.web;

import com.tripbus.catalog.domain.CompanyStatus;
import com.tripbus.catalog.service.CompanyService;
import com.tripbus.catalog.web.dto.CompanyRequest;
import com.tripbus.catalog.web.dto.CompanyResponse;
import com.tripbus.catalog.web.dto.CompanyStatusRequest;
import com.tripbus.catalog.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** US-100: empresas transportadoras. Solo administradores. */
@RestController
@RequestMapping("/companies")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @Operation(summary = "Crear empresa (ADMIN)", description = "Publica CompanyCreated. 409 NIT_ALREADY_EXISTS.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public CompanyResponse create(@Valid @RequestBody CompanyRequest request) {
        return companyService.create(request);
    }

    @Operation(summary = "Listar empresas (ADMIN)", description = "Paginado (page desde 0), filtro opcional por estado.")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<CompanyResponse> list(
            @RequestParam(required = false) CompanyStatus estado,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return companyService.list(estado, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public CompanyResponse get(@PathVariable UUID id) {
        return companyService.get(id);
    }

    @Operation(summary = "Editar empresa (ADMIN)", description = "Publica CompanyUpdated. 409 NIT_ALREADY_EXISTS.")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public CompanyResponse update(@PathVariable UUID id, @Valid @RequestBody CompanyRequest request) {
        return companyService.update(id, request);
    }

    @Operation(summary = "Activar / inactivar empresa (ADMIN)",
            description = "INACTIVA: sus viajes dejan de salir en /trips/search y /trips/in-progress y no puede crear "
                    + "buses; publica CompanyDeactivated. Reactivar publica CompanyUpdated.")
    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasRole('ADMIN')")
    public CompanyResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody CompanyStatusRequest request) {
        return companyService.changeStatus(id, request.estado());
    }
}
