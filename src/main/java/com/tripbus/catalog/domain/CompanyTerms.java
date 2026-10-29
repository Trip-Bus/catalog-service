package com.tripbus.catalog.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "company_terms")
public class CompanyTerms {
    @Id private UUID id;
    @Column(name = "company_id", nullable = false) private UUID companyId;
    @Column(name = "cuota_mensual", nullable = false) private BigDecimal cuotaMensual;
    @Column(name = "comision_porcentaje", nullable = false) private BigDecimal comisionPorcentaje;
    @Column(name = "vigente_desde", nullable = false) private LocalDate vigenteDesde;
    @Column(name = "creado_por", nullable = false) private UUID creadoPor;
    @Column(name = "creado_en", nullable = false) private Instant creadoEn;
    protected CompanyTerms() { }
    public CompanyTerms(UUID companyId, BigDecimal cuota, BigDecimal comision, LocalDate desde, UUID creador, Instant ahora) {
        this.id = UUID.randomUUID(); this.companyId = companyId; this.cuotaMensual = cuota;
        this.comisionPorcentaje = comision; this.vigenteDesde = desde; this.creadoPor = creador; this.creadoEn = ahora;
    }
    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public BigDecimal getCuotaMensual() { return cuotaMensual; }
    public BigDecimal getComisionPorcentaje() { return comisionPorcentaje; }
    public LocalDate getVigenteDesde() { return vigenteDesde; }
    public UUID getCreadoPor() { return creadoPor; }
    public Instant getCreadoEn() { return creadoEn; }
}
