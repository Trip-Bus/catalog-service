package com.tripbus.catalog.web;

import com.tripbus.catalog.service.TerminalService;
import com.tripbus.catalog.web.dto.PlaceResponse;
import com.tripbus.catalog.web.dto.TerminalRequest;
import com.tripbus.catalog.web.dto.TerminalResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** US-115: terminales (GET público, el resto solo ADMIN) y autocompletado de ciudades y terminales. */
@RestController
public class TerminalController {

    private final TerminalService terminalService;

    public TerminalController(TerminalService terminalService) {
        this.terminalService = terminalService;
    }

    @Operation(summary = "Terminales (público)", description = "Ordenadas por ciudad y nombre; ciudad opcional sin tildes.")
    @GetMapping("/terminals")
    public List<TerminalResponse> list(@RequestParam(required = false) String ciudad) {
        return terminalService.list(ciudad);
    }

    @GetMapping("/terminals/{id}")
    public TerminalResponse get(@PathVariable UUID id) {
        return terminalService.get(id);
    }

    @Operation(summary = "Crear terminal (ADMIN)",
            description = "400 INVALID_TERMINAL con details por campo; 409 TERMINAL_ALREADY_EXISTS (mismo nombre en la ciudad).")
    @PostMapping("/terminals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public TerminalResponse create(@RequestBody TerminalRequest request) {
        return terminalService.create(request);
    }

    @PutMapping("/terminals/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public TerminalResponse update(@PathVariable UUID id, @RequestBody TerminalRequest request) {
        return terminalService.update(id, request);
    }

    @Operation(summary = "Borrar terminal (ADMIN)", description = "409 TERMINAL_IN_USE si es parada de alguna ruta.")
    @DeleteMapping("/terminals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID id) {
        terminalService.delete(id);
    }

    @Operation(summary = "Autocompletado de ciudades y terminales (público)",
            description = "Ciudades y terminales cuyo nombre (o ciudad) empieza por q, sin tildes; primero las ciudades, "
                    + "máximo 10. 400 INVALID_QUERY si q está vacío. /trips/cities no cambia.")
    @GetMapping("/places")
    public List<PlaceResponse> places(@RequestParam(required = false) String q) {
        return terminalService.places(q);
    }
}
