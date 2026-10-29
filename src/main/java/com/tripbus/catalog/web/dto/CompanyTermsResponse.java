package com.tripbus.catalog.web.dto;
import com.tripbus.catalog.domain.CompanyTerms;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
public record CompanyTermsResponse(UUID id, UUID companyId, BigDecimal cuotaMensual,
        BigDecimal comisionPorcentaje, LocalDate vigenteDesde, UUID creadoPor, Instant creadoEn) {
    public static CompanyTermsResponse from(CompanyTerms t) { return new CompanyTermsResponse(t.getId(), t.getCompanyId(),
            t.getCuotaMensual(), t.getComisionPorcentaje(), t.getVigenteDesde(), t.getCreadoPor(), t.getCreadoEn()); }
}
