package com.tripbus.catalog.web.dto;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
public record CompanyTermsRequest(@NotNull @DecimalMin("0") BigDecimal cuotaMensual,
        @NotNull @DecimalMin("0") @DecimalMax("30") BigDecimal comisionPorcentaje,
        @NotNull LocalDate vigenteDesde) { }
