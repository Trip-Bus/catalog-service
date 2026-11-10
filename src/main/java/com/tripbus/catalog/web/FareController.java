package com.tripbus.catalog.web;

import com.tripbus.catalog.common.security.CurrentUser;
import com.tripbus.catalog.domain.CategoriaAsiento;
import com.tripbus.catalog.domain.TipoPasajero;
import com.tripbus.catalog.service.FareService;
import com.tripbus.catalog.web.dto.FareDtos.DiscountRequest;
import com.tripbus.catalog.web.dto.FareDtos.PassengerTypeResponse;
import com.tripbus.catalog.web.dto.FareDtos.SeasonRequest;
import com.tripbus.catalog.web.dto.FareDtos.SeasonResponse;
import com.tripbus.catalog.web.dto.FareDtos.SurchargeRequest;
import com.tripbus.catalog.web.dto.FareDtos.SurchargeResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * US-138/US-141: tarifas de la empresa (OPERADOR de la empresa o ADMIN con companyId): temporadas, descuentos por
 * tipo de pasajero y recargos por categoría de asiento. Los precios exactos por tramo van en /routes/{id}/segment-prices.
 */
@RestController
@RequestMapping("/fares")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
public class FareController {

    private final FareService fareService;

    public FareController(FareService fareService) {
        this.fareService = fareService;
    }

    @Operation(summary = "Temporadas", description = "El ADMIN sin companyId ve las de todas las empresas.")
    @GetMapping("/seasons")
    public List<SeasonResponse> seasons(@RequestParam(required = false) UUID companyId, Authentication auth) {
        return fareService.seasons(companyId, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Crear temporada",
            description = "multiplicador entre 0.5 y 3; routeId opcional (de la empresa). Si se cruzan dos temporadas "
                    + "gana la de la ruta y luego la más reciente. 400 INVALID_FARE.")
    @PostMapping("/seasons")
    @ResponseStatus(HttpStatus.CREATED)
    public SeasonResponse createSeason(@RequestBody SeasonRequest request, Authentication auth) {
        return fareService.createSeason(request, CurrentUser.companyScope(auth));
    }

    @PutMapping("/seasons/{id}")
    public SeasonResponse updateSeason(@PathVariable UUID id, @RequestBody SeasonRequest request, Authentication auth) {
        return fareService.updateSeason(id, request, CurrentUser.companyScope(auth));
    }

    @DeleteMapping("/seasons/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSeason(@PathVariable UUID id, Authentication auth) {
        fareService.deleteSeason(id, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Descuentos por tipo de pasajero", description = "ADULTO, NINO y ADULTO_MAYOR (0 % por defecto).")
    @GetMapping("/passenger-types")
    public List<PassengerTypeResponse> passengerTypes(@RequestParam(required = false) UUID companyId,
                                                      Authentication auth) {
        return fareService.passengerTypes(companyId, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Fijar el descuento de un tipo", description = "0 a 100 %; ADULTO siempre 0. 400 INVALID_FARE.")
    @PutMapping("/passenger-types/{tipo}")
    public PassengerTypeResponse putPassengerType(@PathVariable TipoPasajero tipo, @RequestBody DiscountRequest request,
                                                  Authentication auth) {
        return fareService.putPassengerType(tipo, request, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Volver al descuento por defecto (0 %)")
    @DeleteMapping("/passenger-types/{tipo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePassengerType(@PathVariable TipoPasajero tipo, @RequestParam(required = false) UUID companyId,
                                    Authentication auth) {
        fareService.deletePassengerType(tipo, companyId, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Recargos por categoría de asiento", description = "ESTANDAR, PREFERENCIAL y PREMIUM (0 por defecto).")
    @GetMapping("/seat-surcharges")
    public List<SurchargeResponse> surcharges(@RequestParam(required = false) UUID companyId, Authentication auth) {
        return fareService.surcharges(companyId, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Fijar el recargo de una categoría", description = "En COP, no negativo. 400 INVALID_FARE.")
    @PutMapping("/seat-surcharges/{categoria}")
    public SurchargeResponse putSurcharge(@PathVariable CategoriaAsiento categoria,
                                          @RequestBody SurchargeRequest request, Authentication auth) {
        return fareService.putSurcharge(categoria, request, CurrentUser.companyScope(auth));
    }

    @Operation(summary = "Volver al recargo por defecto (0)")
    @DeleteMapping("/seat-surcharges/{categoria}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSurcharge(@PathVariable CategoriaAsiento categoria, @RequestParam(required = false) UUID companyId,
                                Authentication auth) {
        fareService.deleteSurcharge(categoria, companyId, CurrentUser.companyScope(auth));
    }
}
