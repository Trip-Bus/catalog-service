package com.tripbus.catalog.service;

import com.tripbus.catalog.client.SeatInventoryClient;
import com.tripbus.catalog.client.SeatInventoryClient.RemapResult;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.common.events.EventPublisher;
import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.BusStatus;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.domain.ChangeType;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.ItineraryChange;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.events.CatalogEvents;
import com.tripbus.catalog.repository.ItineraryChangeRepository;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.web.dto.ChangeBusRequest;
import com.tripbus.catalog.web.dto.RescheduleRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-118/US-119 y cancelación con sus reglas, sin base de datos (el flujo completo está en CambiosDeViajeIT). */
class ItineraryChangeServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-24T15:00:00Z");
    private static final UUID USUARIO = UUID.randomUUID();
    private static final List<String> ASIENTOS_A = List.of("1", "2", "3", "4");
    private static final List<String> ASIENTOS_B = List.of("1", "2");
    private static final java.util.concurrent.atomic.AtomicInteger PLACAS = new java.util.concurrent.atomic.AtomicInteger();

    private final ItineraryRepository itineraries = mock(ItineraryRepository.class);
    private final ItineraryChangeRepository changes = mock(ItineraryChangeRepository.class);
    private final BusSchedule busSchedule = mock(BusSchedule.class);
    private final ItineraryPublisher itineraryPublisher = mock(ItineraryPublisher.class);
    private final SeatInventoryClient seatInventory = mock(SeatInventoryClient.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final SearchCache cache = mock(SearchCache.class);
    private final ItineraryChangeService service = new ItineraryChangeService(itineraries, changes, busSchedule,
            itineraryPublisher, seatInventory, mock(ItineraryService.class), events, cache,
            Clock.fixed(AHORA, ZoneOffset.UTC), Duration.ofHours(6));

    private Company empresa;
    private Bus busA;
    private Bus busB;
    private Itinerary viaje;

    @BeforeEach
    void setUp() {
        empresa = new Company(UUID.randomUUID(), "Expreso", "900100001");
        Route ruta = new Route(UUID.randomUUID(), empresa, "Bogotá", "Tunja", 120);
        busA = bus(empresa.getId());
        busB = bus(empresa.getId());
        Instant salida = AHORA.plus(1, ChronoUnit.DAYS);
        viaje = new Itinerary(UUID.randomUUID(), ruta, busA, salida, salida.plus(2, ChronoUnit.HOURS),
                new BigDecimal("30000"));
        when(itineraries.findByIdForUpdate(viaje.getId())).thenReturn(Optional.of(viaje));
        when(itineraries.findDetailed(viaje.getId())).thenReturn(Optional.of(viaje));
        when(busSchedule.lock(busA.getId())).thenReturn(Optional.of(busA));
        when(busSchedule.lock(busB.getId())).thenReturn(Optional.of(busB));
        when(busSchedule.findConflictExcept(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(itineraryPublisher.seatNumbers(busA)).thenReturn(ASIENTOS_A);
        when(itineraryPublisher.seatNumbers(busB)).thenReturn(ASIENTOS_B);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void limpiar() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // ---------------------------------------------------------------- US-118

    @Test
    void retrasoHastaElUmbralYReprogramacionDespuesOAlAdelantar() {
        Duration max = Duration.ofHours(6);
        assertThat(ItineraryChangeService.tipo(Duration.ofMinutes(1), max)).isEqualTo(ChangeType.RETRASO);
        assertThat(ItineraryChangeService.tipo(max, max)).isEqualTo(ChangeType.RETRASO);
        assertThat(ItineraryChangeService.tipo(max.plusMinutes(1), max)).isEqualTo(ChangeType.REPROGRAMACION);
        assertThat(ItineraryChangeService.tipo(Duration.ofMinutes(-30), max)).isEqualTo(ChangeType.REPROGRAMACION);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reprogramarRecalculaLaLlegadaGuardaHistorialYPublica() {
        Instant anterior = viaje.getFechaSalida();
        Instant nueva = anterior.plus(90, ChronoUnit.MINUTES);
        var r = service.reschedule(viaje.getId(), new RescheduleRequest(nueva, "Vía cerrada"), empresa.getId(), USUARIO);

        assertThat(r.fechaSalida()).isEqualTo(nueva);
        assertThat(r.fechaLlegadaEstimada()).isEqualTo(nueva.plus(120, ChronoUnit.MINUTES));
        verify(busSchedule).lock(busA.getId());
        verify(busSchedule).findConflictExcept(busA.getId(), nueva, nueva.plus(120, ChronoUnit.MINUTES), viaje.getId());
        ArgumentCaptor<ItineraryChange> cambio = ArgumentCaptor.forClass(ItineraryChange.class);
        verify(changes).save(cambio.capture());
        assertThat(cambio.getValue().getTipo()).isEqualTo(ChangeType.RETRASO);
        assertThat(cambio.getValue().getSalidaAnterior()).isEqualTo(anterior);
        assertThat(cambio.getValue().getHechoPor()).isEqualTo(USUARIO);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publishAfterCommit(eq(CatalogEvents.ITINERARY_RESCHEDULED), payload.capture());
        assertThat(payload.getValue()).containsEntry("minutosDiferencia", 90L).containsEntry("tipo", "RETRASO")
                .containsEntry("fechaSalidaAnterior", anterior).containsEntry("motivo", "Vía cerrada")
                .containsEntry("companyId", empresa.getId()).containsKeys("routeId", "busId", "fechaLlegada");
        verify(cache).evictAll();
    }

    @Test
    void reprogramarConCruceEs409() {
        UUID otro = UUID.randomUUID();
        when(busSchedule.findConflictExcept(any(), any(), any(), any())).thenReturn(Optional.of(otro));
        assertThatThrownBy(() -> service.reschedule(viaje.getId(),
                new RescheduleRequest(viaje.getFechaSalida().plusSeconds(600), "x"), null, USUARIO))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).containsEntry("itineraryId", otro));
        verify(changes, never()).save(any());
    }

    @Test
    void reglasDeLaReprogramacion() {
        Instant manana = viaje.getFechaSalida();
        codigo(() -> service.reschedule(viaje.getId(), new RescheduleRequest(AHORA.minusSeconds(1), "x"), null, USUARIO),
                "DEPARTURE_IN_PAST");
        codigo(() -> service.reschedule(viaje.getId(), new RescheduleRequest(manana, "x"), null, USUARIO),
                "SAME_DEPARTURE");
        codigo(() -> service.reschedule(viaje.getId(), new RescheduleRequest(manana, "x"), UUID.randomUUID(), USUARIO),
                "ITINERARY_NOT_FOUND");
        viaje.reprogramar(AHORA.minusSeconds(60), AHORA.plusSeconds(3600));
        codigo(() -> service.reschedule(viaje.getId(), new RescheduleRequest(manana, "x"), null, USUARIO),
                "ITINERARY_ALREADY_DEPARTED");
        viaje.cancelar();
        codigo(() -> service.reschedule(viaje.getId(), new RescheduleRequest(manana, "x"), null, USUARIO),
                "ITINERARY_CANCELLED");
    }

    // ---------------------------------------------------------------- US-119

    @Test
    @SuppressWarnings("unchecked")
    void cambiarBusReasignaAsientosYPublica() {
        when(seatInventory.remap(viaje.getId(), ASIENTOS_B, 2)).thenReturn(remap(RemapResult.Status.OK));

        var r = service.changeBus(viaje.getId(), new ChangeBusRequest(busB.getId(), "Daño"), empresa.getId(), USUARIO);

        assertThat(r.busId()).isEqualTo(busB.getId());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publishAfterCommit(eq(CatalogEvents.ITINERARY_BUS_CHANGED), payload.capture());
        assertThat(payload.getValue()).containsEntry("busAnterior", busA.getId()).containsEntry("busNuevo", busB.getId())
                .containsEntry("capacidad", 2).containsEntry("seatNumbers", ASIENTOS_B);
        ArgumentCaptor<ItineraryChange> cambio = ArgumentCaptor.forClass(ItineraryChange.class);
        verify(changes).save(cambio.capture());
        assertThat(cambio.getValue().getTipo()).isEqualTo(ChangeType.CAMBIO_BUS);
        assertThat(cambio.getValue().getBusAnterior()).isEqualTo(busA.getId());

        // Confirmado: no se compensa.
        sincronizaciones().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verify(seatInventory, times(1)).remap(any(), anyList(), anyInt());
    }

    @Test
    void siLaTransaccionFallaDespuesDelRemapSeCompensaConLosAsientosAnteriores() {
        when(seatInventory.remap(viaje.getId(), ASIENTOS_B, 2)).thenReturn(remap(RemapResult.Status.OK));
        when(seatInventory.remap(viaje.getId(), ASIENTOS_A, 4)).thenReturn(remap(RemapResult.Status.OK));
        service.changeBus(viaje.getId(), new ChangeBusRequest(busB.getId(), "Daño"), null, USUARIO);

        sincronizaciones().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(seatInventory).remap(viaje.getId(), ASIENTOS_A, 4);
    }

    @Test
    void siLaCompensacionTambienFallaSoloQuedaEnElLog() {
        when(seatInventory.remap(viaje.getId(), ASIENTOS_B, 2)).thenReturn(remap(RemapResult.Status.OK));
        when(seatInventory.remap(viaje.getId(), ASIENTOS_A, 4)).thenReturn(remap(RemapResult.Status.UNAVAILABLE));
        service.changeBus(viaje.getId(), new ChangeBusRequest(busB.getId(), "Daño"), null, USUARIO);

        sincronizaciones().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN));
        verify(seatInventory).remap(viaje.getId(), ASIENTOS_A, 4);
    }

    @Test
    void conflictoDeAsientosOSeatInventoryCaidoNoCambianNada() {
        when(seatInventory.remap(any(), anyList(), anyInt()))
                .thenReturn(new RemapResult(RemapResult.Status.SEATS_CONFLICT, List.of("3", "4")));
        ApiException conflicto = codigo(() -> service.changeBus(viaje.getId(),
                new ChangeBusRequest(busB.getId(), "x"), null, USUARIO), "SEATS_CONFLICT");
        assertThat(conflicto.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflicto.getDetails()).containsEntry("asientos", List.of("3", "4"));

        when(seatInventory.remap(any(), anyList(), anyInt())).thenReturn(remap(RemapResult.Status.UNAVAILABLE));
        ApiException caido = codigo(() -> service.changeBus(viaje.getId(),
                new ChangeBusRequest(busB.getId(), "x"), null, USUARIO), "SEAT_INVENTORY_UNAVAILABLE");
        assertThat(caido.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(viaje.getBus()).isSameAs(busA);
        verify(changes, never()).save(any());
        verify(events, never()).publishAfterCommit(any(), any());
    }

    @Test
    void reglasDelBusNuevo() {
        codigo(() -> service.changeBus(viaje.getId(), new ChangeBusRequest(busA.getId(), "x"), null, USUARIO),
                "SAME_BUS");
        UUID inexistente = UUID.randomUUID();
        when(busSchedule.lock(inexistente)).thenReturn(Optional.empty());
        codigo(() -> service.changeBus(viaje.getId(), new ChangeBusRequest(inexistente, "x"), null, USUARIO),
                "BUS_NOT_FOUND");

        Bus deOtra = bus(UUID.randomUUID());
        when(busSchedule.lock(deOtra.getId())).thenReturn(Optional.of(deOtra));
        codigo(() -> service.changeBus(viaje.getId(), new ChangeBusRequest(deOtra.getId(), "x"), empresa.getId(),
                USUARIO), "BUS_NOT_FOUND");
        codigo(() -> service.changeBus(viaje.getId(), new ChangeBusRequest(deOtra.getId(), "x"), null, USUARIO),
                "COMPANY_MISMATCH");

        busB.cambiarEstado(BusStatus.MANTENIMIENTO, AHORA);
        codigo(() -> service.changeBus(viaje.getId(), new ChangeBusRequest(busB.getId(), "x"), null, USUARIO),
                "BUS_NOT_ACTIVE");
        busB.cambiarEstado(BusStatus.ACTIVO, AHORA);

        when(busSchedule.findConflictExcept(eq(busB.getId()), any(), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));
        codigo(() -> service.changeBus(viaje.getId(), new ChangeBusRequest(busB.getId(), "x"), null, USUARIO),
                BusSchedule.CONFLICT);
        verify(seatInventory, never()).remap(any(), anyList(), anyInt());
    }

    @Test
    void losBusesSeBloqueanEnOrdenDeId() {
        when(seatInventory.remap(any(), anyList(), anyInt())).thenReturn(remap(RemapResult.Status.OK));
        service.changeBus(viaje.getId(), new ChangeBusRequest(busB.getId(), "x"), null, USUARIO);
        var orden = org.mockito.Mockito.inOrder(busSchedule);
        UUID primero = busA.getId().compareTo(busB.getId()) < 0 ? busA.getId() : busB.getId();
        UUID segundo = primero.equals(busA.getId()) ? busB.getId() : busA.getId();
        orden.verify(busSchedule).lock(primero);
        orden.verify(busSchedule).lock(segundo);
    }

    @Test
    void noSeCambiaElBusDeUnViajeCanceladoOTerminado() {
        viaje.reprogramar(AHORA.minus(3, ChronoUnit.HOURS), AHORA.minus(1, ChronoUnit.HOURS));
        codigo(() -> service.changeBus(viaje.getId(), new ChangeBusRequest(busB.getId(), "x"), null, USUARIO),
                "ITINERARY_FINISHED");
        viaje.cancelar();
        codigo(() -> service.changeBus(viaje.getId(), new ChangeBusRequest(busB.getId(), "x"), null, USUARIO),
                "ITINERARY_CANCELLED");
    }

    // ---------------------------------------------------------------- cancelación e historial

    @Test
    @SuppressWarnings("unchecked")
    void cancelarPublicaLoQueBookingNecesita() {
        service.cancel(viaje.getId(), "Paro", empresa.getId(), USUARIO);

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publishAfterCommit(eq(CatalogEvents.ITINERARY_CANCELLED), payload.capture());
        assertThat(payload.getValue()).containsOnlyKeys("itineraryId", "companyId", "routeId", "fechaSalida", "motivo")
                .containsEntry("companyId", empresa.getId()).containsEntry("motivo", "Paro");
        ArgumentCaptor<ItineraryChange> cambio = ArgumentCaptor.forClass(ItineraryChange.class);
        verify(changes).save(cambio.capture());
        assertThat(cambio.getValue().getTipo()).isEqualTo(ChangeType.CANCELACION);

        codigo(() -> service.cancel(viaje.getId(), "otra vez", null, USUARIO), "ITINERARY_ALREADY_CANCELLED");
    }

    @Test
    void reglasDeLaCancelacion() {
        codigo(() -> service.cancel(viaje.getId(), "x", UUID.randomUUID(), USUARIO), "ITINERARY_NOT_FOUND");
        viaje.reprogramar(AHORA.minus(3, ChronoUnit.HOURS), AHORA.minusSeconds(1));
        codigo(() -> service.cancel(viaje.getId(), "x", null, USUARIO), "ITINERARY_FINISHED");
    }

    @Test
    void historialSoloParaLaEmpresaDelViaje() {
        when(changes.findByItineraryIdOrderByCreadoEnAsc(viaje.getId())).thenReturn(List.of(new ItineraryChange(
                viaje.getId(), ChangeType.RETRASO, AHORA, AHORA.plusSeconds(60), null, null, "x", USUARIO, AHORA)));
        assertThat(service.changes(viaje.getId(), empresa.getId())).singleElement()
                .satisfies(c -> assertThat(c.tipo()).isEqualTo("RETRASO"));
        codigo(() -> service.changes(viaje.getId(), UUID.randomUUID()), "ITINERARY_NOT_FOUND");
    }

    private static List<TransactionSynchronization> sincronizaciones() {
        return TransactionSynchronizationManager.getSynchronizations();
    }

    private static RemapResult remap(RemapResult.Status status) {
        return new RemapResult(status, List.of());
    }

    private static Bus bus(UUID companyId) {
        return new Bus(UUID.randomUUID(), companyId, String.format("ABC%03d", PLACAS.incrementAndGet()), BusType.UN_PISO, 4);
    }

    private static ApiException codigo(org.assertj.core.api.ThrowableAssert.ThrowingCallable accion, String codigo) {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(accion);
        assertThat(t).isInstanceOf(ApiException.class);
        assertThat(((ApiException) t).getCode()).isEqualTo(codigo);
        return (ApiException) t;
    }
}
