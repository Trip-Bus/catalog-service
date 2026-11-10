package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.Terminal;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.repository.StopRepository;
import com.tripbus.catalog.repository.TerminalRepository;
import com.tripbus.catalog.web.dto.PlaceResponse;
import com.tripbus.catalog.web.dto.TerminalRequest;
import com.tripbus.catalog.web.dto.TerminalResponse;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * US-115: terminales de transporte (CRUD del ADMIN; la consulta es pública) y autocompletado de ciudades y
 * terminales en /places.
 */
@Service
public class TerminalService {

    public static final String ERROR = "INVALID_TERMINAL";
    static final int MAX_SUGERENCIAS = 10;
    static final int MAX_DIRECCION = 200;

    private final TerminalRepository terminalRepository;
    private final StopRepository stopRepository;
    private final RouteRepository routeRepository;

    public TerminalService(TerminalRepository terminalRepository, StopRepository stopRepository,
                           RouteRepository routeRepository) {
        this.terminalRepository = terminalRepository;
        this.stopRepository = stopRepository;
        this.routeRepository = routeRepository;
    }

    @Transactional(readOnly = true)
    public List<TerminalResponse> list(String ciudad) {
        String ciudadNorm = TextNormalizer.normalize(ciudad);
        List<Terminal> terminales = ciudadNorm.isEmpty() ? terminalRepository.findAllByOrderByCiudadAscNombreAsc()
                : terminalRepository.findByCiudadNormOrderByNombreAsc(ciudadNorm);
        return terminales.stream().map(TerminalResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public TerminalResponse get(UUID id) {
        return TerminalResponse.from(find(id));
    }

    @Transactional
    public TerminalResponse create(TerminalRequest request) {
        UUID id = UUID.randomUUID();
        validar(request, id);
        return TerminalResponse.from(terminalRepository.save(new Terminal(id, request.ciudad().trim(),
                request.nombre().trim(), direccion(request), request.lat(), request.lng())));
    }

    @Transactional
    public TerminalResponse update(UUID id, TerminalRequest request) {
        Terminal terminal = find(id);
        validar(request, id);
        terminal.actualizar(request.ciudad().trim(), request.nombre().trim(), direccion(request), request.lat(),
                request.lng());
        return TerminalResponse.from(terminal);
    }

    /** 409 TERMINAL_IN_USE si alguna parada de una ruta la usa. */
    @Transactional
    public void delete(UUID id) {
        Terminal terminal = find(id);
        if (stopRepository.existsByTerminalId(id)) {
            throw ApiException.conflict("TERMINAL_IN_USE", "La terminal es parada de alguna ruta");
        }
        terminalRepository.delete(terminal);
    }

    /**
     * US-115: ciudades del catálogo (las de /trips/cities) y terminales cuyo nombre o ciudad empiezan por q, sin
     * distinguir tildes ni mayúsculas; primero las ciudades, máximo 10 en total.
     */
    @Transactional(readOnly = true)
    public List<PlaceResponse> places(String q) {
        String prefijo = TextNormalizer.normalize(q);
        if (prefijo.isEmpty()) {
            throw ApiException.badRequest("INVALID_QUERY", "q es obligatorio");
        }
        TreeSet<String> ciudades = new TreeSet<>(routeRepository.findOrigenes());
        ciudades.addAll(routeRepository.findDestinos());
        List<PlaceResponse> sugerencias = new ArrayList<>(ciudades.stream()
                .filter(c -> TextNormalizer.normalize(c).startsWith(prefijo))
                .limit(MAX_SUGERENCIAS)
                .map(c -> new PlaceResponse(PlaceResponse.CIUDAD, c, c, null))
                .toList());
        int restantes = MAX_SUGERENCIAS - sugerencias.size();
        if (restantes > 0) {
            terminalRepository.buscarPorPrefijo(prefijo, Limit.of(restantes)).forEach(t -> sugerencias.add(
                    new PlaceResponse(PlaceResponse.TERMINAL, t.getNombre(), t.getCiudad(), t.getId())));
        }
        return sugerencias;
    }

    private void validar(TerminalRequest r, UUID id) {
        Map<String, Object> errores = new LinkedHashMap<>();
        texto(errores, "ciudad", r.ciudad(), RouteValidator.MAX_CIUDAD, "La ciudad");
        texto(errores, "nombre", r.nombre(), RouteValidator.MAX_PARADA, "El nombre");
        if (r.direccion() != null && r.direccion().trim().length() > MAX_DIRECCION) {
            errores.put("direccion", "La dirección no puede superar " + MAX_DIRECCION + " caracteres");
        }
        if (r.lat() == null || r.lat() < RouteValidator.LAT_MIN || r.lat() > RouteValidator.LAT_MAX) {
            errores.put("lat", "La latitud debe estar en Colombia");
        }
        if (r.lng() == null || r.lng() < RouteValidator.LNG_MIN || r.lng() > RouteValidator.LNG_MAX) {
            errores.put("lng", "La longitud debe estar en Colombia");
        }
        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ERROR, "La terminal no es válida", errores);
        }
        if (terminalRepository.existsByCiudadNormAndNombreNormAndIdNot(TextNormalizer.normalize(r.ciudad()),
                TextNormalizer.normalize(r.nombre()), id)) {
            throw ApiException.conflict("TERMINAL_ALREADY_EXISTS", "Ya existe una terminal con ese nombre en la ciudad");
        }
    }

    private static void texto(Map<String, Object> errores, String campo, String valor, int max, String nombre) {
        if (valor == null || valor.isBlank()) {
            errores.put(campo, nombre + " es obligatorio");
        } else if (valor.trim().length() > max) {
            errores.put(campo, nombre + " no puede superar " + max + " caracteres");
        }
    }

    private static String direccion(TerminalRequest r) {
        return r.direccion() == null || r.direccion().isBlank() ? null : r.direccion().trim();
    }

    private Terminal find(UUID id) {
        return terminalRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("TERMINAL_NOT_FOUND", "La terminal no existe"));
    }
}
