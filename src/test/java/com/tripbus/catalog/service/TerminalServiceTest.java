package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.Terminal;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.repository.StopRepository;
import com.tripbus.catalog.repository.TerminalRepository;
import com.tripbus.catalog.web.dto.PlaceResponse;
import com.tripbus.catalog.web.dto.TerminalRequest;
import com.tripbus.catalog.web.dto.TerminalResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-115: terminales y autocompletado. */
class TerminalServiceTest {

    private final TerminalRepository terminals = mock(TerminalRepository.class);
    private final StopRepository stops = mock(StopRepository.class);
    private final RouteRepository routes = mock(RouteRepository.class);
    private final TerminalService service = new TerminalService(terminals, stops, routes);

    private final Terminal salitre = new Terminal(UUID.randomUUID(), "Bogotá", "Terminal Salitre", "Dg 23", 4.65, -74.11);

    @Test
    void listaTodasOLasDeUnaCiudadSinTildes() {
        when(terminals.findAllByOrderByCiudadAscNombreAsc()).thenReturn(List.of(salitre));
        when(terminals.findByCiudadNormOrderByNombreAsc("bogota")).thenReturn(List.of(salitre));

        assertThat(service.list(null)).extracting(TerminalResponse::nombre).containsExactly("Terminal Salitre");
        assertThat(service.list(" BOGOTÁ ")).extracting(TerminalResponse::ciudad).containsExactly("Bogotá");
    }

    @Test
    void creaYEditaConDatosNormalizados() {
        when(terminals.save(any(Terminal.class))).thenAnswer(i -> i.getArgument(0));

        TerminalResponse creada = service.create(new TerminalRequest(" Tunja ", " Terminal Norte ", "  ", 5.5, -73.3));

        assertThat(creada.ciudad()).isEqualTo("Tunja");
        assertThat(creada.nombre()).isEqualTo("Terminal Norte");
        assertThat(creada.direccion()).isNull();

        when(terminals.findById(salitre.getId())).thenReturn(Optional.of(salitre));
        TerminalResponse editada = service.update(salitre.getId(),
                new TerminalRequest("Bogotá", "Terminal El Salitre", " Diagonal 23 ", 4.66, -74.12));
        assertThat(editada.nombre()).isEqualTo("Terminal El Salitre");
        assertThat(editada.direccion()).isEqualTo("Diagonal 23");
        assertThat(salitre.getNombreNorm()).isEqualTo("terminal el salitre");
        assertThat(service.get(salitre.getId()).lat()).isEqualTo(4.66);
    }

    @Test
    void validaCadaCampo() {
        assertThat(errores(new TerminalRequest(null, " ", "x".repeat(201), null, null)))
                .containsKeys("ciudad", "nombre", "direccion", "lat", "lng");
        assertThat(errores(new TerminalRequest("x".repeat(81), "x".repeat(121), null, 40.0, -3.0)))
                .containsKeys("ciudad", "nombre", "lat", "lng");
        assertThat(errores(new TerminalRequest("Cali", "T", null, -5.0, -80.0))).containsOnlyKeys("lat", "lng");
    }

    @Test
    void nombreRepetidoEnLaCiudadEsConflicto() {
        when(terminals.existsByCiudadNormAndNombreNormAndIdNot(anyString(), anyString(), any())).thenReturn(true);

        TerminalRequest repetida = new TerminalRequest("Bogotá", "Terminal Salitre", null, 4.6, -74.1);
        assertThatThrownBy(() -> service.create(repetida))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("TERMINAL_ALREADY_EXISTS");
    }

    @Test
    void borrarNoSePuedeSiEsParadaYLaQueNoExisteEs404() {
        when(terminals.findById(salitre.getId())).thenReturn(Optional.of(salitre));
        when(stops.existsByTerminalId(salitre.getId())).thenReturn(true);
        UUID id = salitre.getId();
        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("TERMINAL_IN_USE");
        verify(terminals, never()).delete(any());

        when(stops.existsByTerminalId(salitre.getId())).thenReturn(false);
        service.delete(id);
        verify(terminals).delete(salitre);

        UUID otra = UUID.randomUUID();
        when(terminals.findById(otra)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(otra))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("TERMINAL_NOT_FOUND");
    }

    @Test
    void autocompletaCiudadesPrimeroYLuegoTerminalesSinTildes() {
        when(routes.findOrigenes()).thenReturn(List.of("Bogotá", "Medellín", "Bucaramanga"));
        when(routes.findDestinos()).thenReturn(List.of("Bogotá", "Barbosa"));
        when(terminals.buscarPorPrefijo("b", Limit.of(7))).thenReturn(List.of(salitre));

        List<PlaceResponse> r = service.places(" B ");

        assertThat(r).extracting(PlaceResponse::nombre)
                .containsExactly("Barbosa", "Bogotá", "Bucaramanga", "Terminal Salitre");
        assertThat(r.get(0).tipo()).isEqualTo(PlaceResponse.CIUDAD);
        assertThat(r.get(3).tipo()).isEqualTo(PlaceResponse.TERMINAL);
        assertThat(r.get(3).ciudad()).isEqualTo("Bogotá");
        assertThat(r.get(3).terminalId()).isEqualTo(salitre.getId());
        assertThat(service.places("medell")).extracting(PlaceResponse::nombre).containsExactly("Medellín");
    }

    @Test
    void conDiezCiudadesNoBuscaTerminalesYSinTextoEsError() {
        when(routes.findOrigenes()).thenReturn(IntStream.range(0, 12).mapToObj(i -> "Ciudad " + i).toList());
        when(routes.findDestinos()).thenReturn(List.of());

        assertThat(service.places("ciudad")).hasSize(TerminalService.MAX_SUGERENCIAS);
        verify(terminals, never()).buscarPorPrefijo(anyString(), any());
        assertThatThrownBy(() -> service.places(" "))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_QUERY");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> errores(TerminalRequest request) {
        try {
            service.create(request);
            throw new AssertionError("Debía fallar");
        } catch (ApiException e) {
            assertThat(e.getCode()).isEqualTo(TerminalService.ERROR);
            return e.getDetails();
        }
    }
}
