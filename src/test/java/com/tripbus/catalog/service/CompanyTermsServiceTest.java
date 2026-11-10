package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.common.events.EventPublisher;
import com.tripbus.catalog.domain.CompanyTerms;
import com.tripbus.catalog.repository.CompanyRepository;
import com.tripbus.catalog.repository.CompanyTermsRepository;
import com.tripbus.catalog.web.dto.CompanyTermsRequest;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompanyTermsServiceTest {
 private static final UUID COMPANY=UUID.randomUUID();
 private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-28T15:00:00Z"),ZoneOffset.UTC);
 private final CompanyTermsRepository repo=mock(CompanyTermsRepository.class);
 private final CompanyRepository companies=mock(CompanyRepository.class);
 private final EventPublisher events=mock(EventPublisher.class);
 private final CompanyTermsService service=new CompanyTermsService(repo,companies,events,CLOCK,new BigDecimal("5"),BigDecimal.ZERO);
 @Test void creaYPublicaLaVigenciaNueva(){
  when(companies.existsById(COMPANY)).thenReturn(true);when(repo.existsByCompanyIdAndVigenteDesde(COMPANY,LocalDate.of(2026,11,1))).thenReturn(false);
  when(repo.save(any())).thenAnswer(inv->inv.getArgument(0));
  var created=service.create(COMPANY,new CompanyTermsRequest(new BigDecimal("125000"),new BigDecimal("12.5"),LocalDate.of(2026,11,1)),UUID.randomUUID());
  assertThat(created.comisionPorcentaje()).isEqualByComparingTo("12.5");verify(events).publishAfterCommit(eq("CompanyTermsChanged"),any());
 }
 @Test void noPermiteFechasPasadasDuplicadasNiEmpresasInexistentes(){
  when(companies.existsById(COMPANY)).thenReturn(true);
  assertThatThrownBy(()->service.create(COMPANY,new CompanyTermsRequest(BigDecimal.ZERO,new BigDecimal("5"),LocalDate.of(2026,10,27)),UUID.randomUUID())).isInstanceOf(ApiException.class);
  when(repo.existsByCompanyIdAndVigenteDesde(eq(COMPANY),any())).thenReturn(true);
  assertThatThrownBy(()->service.create(COMPANY,new CompanyTermsRequest(BigDecimal.ZERO,new BigDecimal("5"),LocalDate.of(2026,10,28)),UUID.randomUUID())).isInstanceOf(ApiException.class);
  when(companies.existsById(any())).thenReturn(false);
  assertThatThrownBy(()->service.create(UUID.randomUUID(),new CompanyTermsRequest(BigDecimal.ZERO,new BigDecimal("5"),LocalDate.now()),UUID.randomUUID())).isInstanceOf(ApiException.class);
 }
 @Test void listaHistorialYConsultaLaVigenciaEnFecha(){
  when(companies.existsById(COMPANY)).thenReturn(true);
  CompanyTerms terms=new CompanyTerms(COMPANY,BigDecimal.ZERO,new BigDecimal("5"),LocalDate.of(2026,1,1),UUID.randomUUID(),CLOCK.instant());
  when(repo.findByCompanyIdOrderByVigenteDesdeDesc(COMPANY)).thenReturn(List.of(terms));
  when(repo.findFirstByCompanyIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(COMPANY,LocalDate.of(2026,10,28))).thenReturn(Optional.of(terms));
  assertThat(service.history(COMPANY)).hasSize(1);assertThat(service.current(COMPANY,LocalDate.of(2026,10,28))).isSameAs(terms);
  when(companies.existsById(any())).thenReturn(false);
  assertThatThrownBy(()->service.history(UUID.randomUUID())).isInstanceOf(ApiException.class);
 }
 @Test void creaElTerminoInicialSoloCuandoNoHayUnaVigenciaActual(){
  when(repo.findFirstByCompanyIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(eq(COMPANY),any())).thenReturn(Optional.empty());
  service.ensureInitial(COMPANY);verify(repo).save(any(CompanyTerms.class));
  when(repo.findFirstByCompanyIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(eq(COMPANY),any()))
          .thenReturn(Optional.of(mock(CompanyTerms.class)));
  service.ensureInitial(COMPANY);verify(repo,times(1)).save(any(CompanyTerms.class));
  when(companies.existsById(any())).thenReturn(true);
  when(repo.findFirstByCompanyIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(any(),any())).thenReturn(Optional.empty());
  assertThatThrownBy(()->service.current(COMPANY,LocalDate.now(CLOCK))).isInstanceOf(ApiException.class);
 }
}
