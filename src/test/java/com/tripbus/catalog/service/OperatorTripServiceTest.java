package com.tripbus.catalog.service;

import com.tripbus.catalog.client.SeatInventoryClient;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.*;
import com.tripbus.catalog.repository.CompanyRepository;
import com.tripbus.catalog.repository.ItineraryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OperatorTripServiceTest {
 private static final Instant NOW=Instant.parse("2026-10-28T15:00:00Z");
 private final ItineraryRepository repo=mock(ItineraryRepository.class);
 private final CompanyRepository companies=mock(CompanyRepository.class);
 private final SeatInventoryClient seats=mock(SeatInventoryClient.class);
 private final OperatorTripService service=new OperatorTripService(repo,companies,seats,Clock.fixed(NOW,ZoneOffset.UTC));
 @Test void agregaOcupacionYCandidatosEnVeinticuatroHoras(){
  UUID company=UUID.randomUUID();when(companies.existsById(company)).thenReturn(true);
  Itinerary trip=trip(company,NOW.plusSeconds(3600));trip.marcarOferta(UUID.randomUUID(),new BigDecimal("40000"),NOW.plusSeconds(7200));
  Itinerary late=trip(company,NOW.plus(Duration.ofHours(25)));
  when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class),any(org.springframework.data.domain.Pageable.class))).thenReturn(new PageImpl<>(List.of(trip,late)));
  when(seats.occupancy(anyCollection())).thenReturn(Optional.of(Map.of(
          trip.getId(),new SeatInventoryClient.Occupancy(10,30,0,new BigDecimal("25"),new BigDecimal("2")),
          late.getId(),new SeatInventoryClient.Occupancy(24,16,0,new BigDecimal("40"),BigDecimal.ZERO))));
  var result=service.list(null,null,company,null,0,50);
  assertThat(result.resumen().viajes()).isEqualTo(2);assertThat(result.resumen().candidatosOferta()).isEqualTo(1);
  assertThat(result.resumen().ocupacionPromedio()).isEqualByComparingTo("32.50");assertThat(result.content().getFirst().ocupacionConocida()).isTrue();
  assertThat(result.content().getFirst().ofertaActiva()).isNotNull();
  verify(seats).occupancy(List.of(trip.getId(),late.getId()));
 }
 @Test void sigueConOcupacionDesconocidaYValidaEmpresaYFechas(){
  UUID company=UUID.randomUUID();when(companies.existsById(company)).thenReturn(true);
  Itinerary trip=trip(company,NOW.plusSeconds(3600));
  when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class),any(org.springframework.data.domain.Pageable.class))).thenReturn(new PageImpl<>(List.of(trip)));
  when(seats.occupancy(anyCollection())).thenReturn(Optional.empty());
  var result=service.list(null,null,company,null,0,50);assertThat(result.resumen().ocupacionPromedio()).isNull();
  assertThat(result.content().getFirst().ocupacionConocida()).isFalse();assertThat(result.content().getFirst().ocupacion()).isNull();
  assertThatThrownBy(()->service.list(LocalDate.of(2026,10,1),LocalDate.of(2026,11,2),company,null,0,50)).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->service.list(LocalDate.of(2026,10,29),LocalDate.of(2026,10,28),company,null,0,50)).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->service.list(null,null,company,null,-1,50)).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->service.list(null,null,company,null,0,0)).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->service.list(null,null,company,null,0,101)).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->service.list(null,null,null,null,0,50)).isInstanceOf(ApiException.class);
  UUID missing=UUID.randomUUID();when(companies.existsById(missing)).thenReturn(false);
  assertThatThrownBy(()->service.list(null,null,missing,null,0,50)).isInstanceOf(ApiException.class);
 }
 @Test void aceptaElLimiteMaximoDeTreintaDiasEntreFechas(){
  UUID company=UUID.randomUUID();when(companies.existsById(company)).thenReturn(true);
  when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class),any(org.springframework.data.domain.Pageable.class))).thenReturn(new PageImpl<>(List.of()));
  when(seats.occupancy(anyCollection())).thenReturn(Optional.of(Map.of()));
  var page=service.list(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),company,null,0,100);
  assertThat(page.content()).isEmpty();
 }
 private static Itinerary trip(UUID company,Instant departure){
  Company c=new Company(company,"Empresa",null);Route route=new Route(UUID.randomUUID(),c,"Bogotá","Cali",300);
  Bus bus=new Bus(UUID.randomUUID(),company,"ABC123",BusType.UN_PISO,40);
  return new Itinerary(UUID.randomUUID(),route,bus,departure,departure.plusSeconds(18000),new BigDecimal("50000"));
 }
}
