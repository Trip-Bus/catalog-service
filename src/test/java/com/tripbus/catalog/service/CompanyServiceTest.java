package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.common.events.EventPublisher;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.CompanyStatus;
import com.tripbus.catalog.events.CatalogEvents;
import com.tripbus.catalog.repository.CompanyRepository;
import com.tripbus.catalog.web.dto.CompanyRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-21T15:00:00Z");

    private final CompanyRepository repository = mock(CompanyRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final SearchCache cache = mock(SearchCache.class);
    private final CompanyService service =
            new CompanyService(repository, publisher, cache, Clock.fixed(AHORA, ZoneOffset.UTC));

    @Test
    void nitBaseIgnoraElDigitoDeVerificacion() {
        assertThat(CompanyService.nitBase("900123456-7")).isEqualTo("900123456");
        assertThat(CompanyService.nitBase("900123456")).isEqualTo("900123456");
    }

    @Test
    void inactivarPublicaCompanyDeactivatedEInvalidaLaCache() {
        Company empresa = empresa();
        var r = service.changeStatus(empresa.getId(), CompanyStatus.INACTIVA);
        assertThat(r.estado()).isEqualTo("INACTIVA");
        assertThat(r.actualizadoEn()).isEqualTo(AHORA);
        verify(publisher).publishAfterCommit(eq(CatalogEvents.COMPANY_DEACTIVATED), any());
        verify(cache).evictAll();
    }

    @Test
    void reactivarPublicaCompanyUpdated() {
        Company empresa = empresa();
        empresa.cambiarEstado(CompanyStatus.INACTIVA, AHORA);
        service.changeStatus(empresa.getId(), CompanyStatus.ACTIVA);
        verify(publisher).publishAfterCommit(eq(CatalogEvents.COMPANY_UPDATED), any());
        verify(cache).evictAll();
    }

    @Test
    void elMismoEstadoNoPublicaNiInvalida() {
        Company empresa = empresa();
        service.changeStatus(empresa.getId(), CompanyStatus.ACTIVA);
        verify(publisher, never()).publishAfterCommit(anyString(), any());
        verify(cache, never()).evictAll();
    }

    @Test
    void unaEmpresaInactivaNoPuedeCrearBuses() {
        Company empresa = empresa();
        assertThat(service.requireActive(empresa.getId())).isSameAs(empresa);
        empresa.cambiarEstado(CompanyStatus.INACTIVA, AHORA);
        assertThatThrownBy(() -> service.requireActive(empresa.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("COMPANY_INACTIVE");
    }

    @Test
    void nitRepetidoEs409() {
        when(repository.existsNitBase(eq("900123456"), any())).thenReturn(true);
        assertThatThrownBy(() -> service.create(new CompanyRequest("Otra", "900123456-1")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("NIT_ALREADY_EXISTS");
    }

    private Company empresa() {
        Company empresa = new Company(UUID.randomUUID(), "Expreso Prueba", "900123456-7");
        when(repository.findById(empresa.getId())).thenReturn(Optional.of(empresa));
        return empresa;
    }
}
