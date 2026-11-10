package com.tripbus.catalog.service;

import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.CategoriaAsiento;
import com.tripbus.catalog.domain.FareSeason;
import com.tripbus.catalog.domain.PassengerTypeFare;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.domain.SeatSurcharge;
import com.tripbus.catalog.domain.SegmentPrice;
import com.tripbus.catalog.domain.TipoPasajero;
import com.tripbus.catalog.repository.FareSeasonRepository;
import com.tripbus.catalog.repository.PassengerTypeFareRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.repository.SeatSurchargeRepository;
import com.tripbus.catalog.repository.SegmentPriceRepository;
import com.tripbus.catalog.repository.StopRepository;
import com.tripbus.catalog.web.dto.FareDtos.DiscountRequest;
import com.tripbus.catalog.web.dto.FareDtos.PassengerTypeResponse;
import com.tripbus.catalog.web.dto.FareDtos.SeasonRequest;
import com.tripbus.catalog.web.dto.FareDtos.SeasonResponse;
import com.tripbus.catalog.web.dto.FareDtos.SegmentPriceRequest;
import com.tripbus.catalog.web.dto.FareDtos.SegmentPriceResponse;
import com.tripbus.catalog.web.dto.FareDtos.SurchargeRequest;
import com.tripbus.catalog.web.dto.FareDtos.SurchargeResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * US-138/US-141: tarifas de una empresa. {@code companyScope} es la empresa del OPERADOR o null para el ADMIN
 * (que indica companyId); lo de otra empresa responde 404. Errores de validación: 400 INVALID_FARE con details.
 */
@Service
public class FareService {

    public static final String ERROR = "INVALID_FARE";
    static final BigDecimal MULTIPLICADOR_MIN = new BigDecimal("0.5");
    static final BigDecimal MULTIPLICADOR_MAX = new BigDecimal("3");
    static final int MAX_NOMBRE = 80;

    private final FareSeasonRepository seasonRepository;
    private final PassengerTypeFareRepository passengerTypeRepository;
    private final SeatSurchargeRepository surchargeRepository;
    private final SegmentPriceRepository segmentPriceRepository;
    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final CompanyService companyService;
    private final SearchCache searchCache;
    private final Clock clock;

    public FareService(FareSeasonRepository seasonRepository, PassengerTypeFareRepository passengerTypeRepository,
                       SeatSurchargeRepository surchargeRepository, SegmentPriceRepository segmentPriceRepository,
                       RouteRepository routeRepository, StopRepository stopRepository, CompanyService companyService,
                       SearchCache searchCache, Clock clock) {
        this.seasonRepository = seasonRepository;
        this.passengerTypeRepository = passengerTypeRepository;
        this.surchargeRepository = surchargeRepository;
        this.segmentPriceRepository = segmentPriceRepository;
        this.routeRepository = routeRepository;
        this.stopRepository = stopRepository;
        this.companyService = companyService;
        this.searchCache = searchCache;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- temporadas

    /** El ADMIN sin companyId ve las de todas las empresas. */
    @Transactional(readOnly = true)
    public List<SeasonResponse> seasons(UUID companyId, UUID companyScope) {
        UUID empresa = companyScope != null ? companyScope : companyId;
        List<FareSeason> temporadas = empresa == null ? seasonRepository.findAllByOrderByDesdeAscNombreAsc()
                : seasonRepository.findByCompanyIdOrderByDesdeAscNombreAsc(empresa);
        return temporadas.stream().map(SeasonResponse::from).toList();
    }

    @Transactional
    public SeasonResponse createSeason(SeasonRequest request, UUID companyScope) {
        UUID companyId = empresaActiva(request.companyId(), companyScope);
        validarTemporada(request, companyId);
        return SeasonResponse.from(seasonRepository.save(new FareSeason(UUID.randomUUID(), companyId,
                request.routeId(), request.nombre().trim(), request.desde(), request.hasta(), request.multiplicador(),
                clock.instant())));
    }

    @Transactional
    public SeasonResponse updateSeason(UUID id, SeasonRequest request, UUID companyScope) {
        FareSeason temporada = temporada(id, companyScope);
        validarTemporada(request, temporada.getCompanyId());
        temporada.actualizar(request.routeId(), request.nombre().trim(), request.desde(), request.hasta(),
                request.multiplicador());
        return SeasonResponse.from(temporada);
    }

    @Transactional
    public void deleteSeason(UUID id, UUID companyScope) {
        seasonRepository.delete(temporada(id, companyScope));
    }

    private void validarTemporada(SeasonRequest r, UUID companyId) {
        Map<String, Object> errores = new LinkedHashMap<>();
        if (r.nombre() == null || r.nombre().isBlank() || r.nombre().trim().length() > MAX_NOMBRE) {
            errores.put("nombre", "El nombre es obligatorio (máximo " + MAX_NOMBRE + " caracteres)");
        }
        if (r.desde() == null || r.hasta() == null || r.desde().isAfter(r.hasta())) {
            errores.put("hasta", "desde y hasta son obligatorios y desde no puede ser posterior a hasta");
        }
        if (r.multiplicador() == null || r.multiplicador().compareTo(MULTIPLICADOR_MIN) < 0
                || r.multiplicador().compareTo(MULTIPLICADOR_MAX) > 0) {
            errores.put("multiplicador", "El multiplicador debe estar entre 0.5 y 3");
        }
        if (r.routeId() != null && routeRepository.findWithCompany(r.routeId())
                .filter(route -> route.getCompany().getId().equals(companyId)).isEmpty()) {
            errores.put("routeId", "La ruta no existe o es de otra empresa");
        }
        lanzarSiHay(errores);
    }

    private FareSeason temporada(UUID id, UUID companyScope) {
        return seasonRepository.findById(id)
                .filter(s -> companyScope == null || companyScope.equals(s.getCompanyId()))
                .orElseThrow(() -> ApiException.notFound("SEASON_NOT_FOUND", "La temporada no existe"));
    }

    // ---------------------------------------------------------------- tipos de pasajero

    /** Los tres tipos con su descuento (0 % si la empresa no lo configuró). */
    @Transactional(readOnly = true)
    public List<PassengerTypeResponse> passengerTypes(UUID companyId, UUID companyScope) {
        UUID empresa = CompanyService.resolveCompany(companyId, companyScope);
        List<PassengerTypeFare> filas = passengerTypeRepository.findByCompanyId(empresa);
        return Arrays.stream(TipoPasajero.values()).map(t -> new PassengerTypeResponse(t.name(), filas.stream()
                .filter(f -> f.getTipo() == t).map(PassengerTypeFare::getDescuentoPorcentaje).findFirst()
                .orElse(BigDecimal.ZERO))).toList();
    }

    /** ADULTO nunca tiene descuento; NINO y ADULTO_MAYOR entre 0 y 100 %. */
    @Transactional
    public PassengerTypeResponse putPassengerType(TipoPasajero tipo, DiscountRequest request, UUID companyScope) {
        UUID empresa = empresaActiva(request.companyId(), companyScope);
        BigDecimal descuento = request.descuentoPorcentaje();
        if (descuento == null || descuento.signum() < 0 || descuento.compareTo(PricingRules.CIEN) > 0
                || tipo == TipoPasajero.ADULTO && descuento.signum() != 0) {
            throw invalida(Map.of("descuentoPorcentaje", "Entre 0 y 100; ADULTO siempre 0"));
        }
        PassengerTypeFare fila = passengerTypeRepository.findByCompanyIdAndTipo(empresa, tipo)
                .orElseGet(() -> new PassengerTypeFare(UUID.randomUUID(), empresa, tipo, descuento));
        fila.cambiarDescuento(descuento);
        passengerTypeRepository.save(fila);
        return new PassengerTypeResponse(tipo.name(), descuento);
    }

    /** Vuelve al descuento por defecto (0 %). */
    @Transactional
    public void deletePassengerType(TipoPasajero tipo, UUID companyId, UUID companyScope) {
        UUID empresa = CompanyService.resolveCompany(companyId, companyScope);
        passengerTypeRepository.findByCompanyIdAndTipo(empresa, tipo).ifPresent(passengerTypeRepository::delete);
    }

    // ---------------------------------------------------------------- recargos por categoría de asiento

    /** Las tres categorías con su recargo (0 si la empresa no lo configuró). */
    @Transactional(readOnly = true)
    public List<SurchargeResponse> surcharges(UUID companyId, UUID companyScope) {
        UUID empresa = CompanyService.resolveCompany(companyId, companyScope);
        List<SeatSurcharge> filas = surchargeRepository.findByCompanyId(empresa);
        return Arrays.stream(CategoriaAsiento.values()).map(c -> new SurchargeResponse(c.name(), filas.stream()
                .filter(f -> f.getCategoria() == c).map(SeatSurcharge::getRecargo).findFirst()
                .orElse(BigDecimal.ZERO))).toList();
    }

    @Transactional
    public SurchargeResponse putSurcharge(CategoriaAsiento categoria, SurchargeRequest request, UUID companyScope) {
        UUID empresa = empresaActiva(request.companyId(), companyScope);
        BigDecimal recargo = request.recargo();
        if (recargo == null || recargo.signum() < 0) {
            throw invalida(Map.of("recargo", "El recargo es obligatorio y no puede ser negativo"));
        }
        SeatSurcharge fila = surchargeRepository.findByCompanyIdAndCategoria(empresa, categoria)
                .orElseGet(() -> new SeatSurcharge(UUID.randomUUID(), empresa, categoria, recargo));
        fila.cambiarRecargo(recargo);
        surchargeRepository.save(fila);
        return new SurchargeResponse(categoria.name(), recargo);
    }

    /** Vuelve al recargo por defecto (0). */
    @Transactional
    public void deleteSurcharge(CategoriaAsiento categoria, UUID companyId, UUID companyScope) {
        UUID empresa = CompanyService.resolveCompany(companyId, companyScope);
        surchargeRepository.findByCompanyIdAndCategoria(empresa, categoria).ifPresent(surchargeRepository::delete);
    }

    // ---------------------------------------------------------------- precios exactos por tramo

    @Transactional(readOnly = true)
    public List<SegmentPriceResponse> segmentPrices(UUID routeId, UUID companyScope) {
        ruta(routeId, companyScope);
        return segmentPriceRepository.findByRouteIdOrderByDesdeOrdenAscHastaOrdenAsc(routeId).stream()
                .map(SegmentPriceResponse::from).toList();
    }

    /**
     * Reemplaza la lista completa. Cada tramo va de una parada a otra posterior de la ruta, sin repetirse y sin ser
     * el recorrido completo (ese vale el precio base del viaje); precio mayor que 0. Invalida la cache de búsquedas.
     */
    @Transactional
    public List<SegmentPriceResponse> replaceSegmentPrices(UUID routeId, List<SegmentPriceRequest> precios,
                                                           UUID companyScope) {
        ruta(routeId, companyScope);
        int paradas = stopRepository.findByRouteIdOrderByOrdenAsc(routeId).size();
        List<SegmentPriceRequest> lista = precios == null ? List.of() : precios;
        Map<String, Object> errores = new LinkedHashMap<>();
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < lista.size(); i++) {
            SegmentPriceRequest p = lista.get(i);
            String campo = "precios[" + i + "]";
            if (p == null || p.desdeOrden() == null || p.hastaOrden() == null || p.desdeOrden() < 1
                    || p.hastaOrden() > paradas || p.desdeOrden() >= p.hastaOrden()
                    || p.desdeOrden() == 1 && p.hastaOrden() == paradas) {
                errores.put(campo, "El tramo debe ir de una parada a otra posterior (1 a " + paradas
                        + ") y no ser el recorrido completo");
            } else if (!vistos.add(p.desdeOrden() + "-" + p.hastaOrden())) {
                errores.put(campo, "El tramo está repetido");
            }
            if (p != null && (p.precio() == null || p.precio().signum() <= 0)) {
                errores.put(campo + ".precio", "El precio debe ser mayor que 0");
            }
        }
        lanzarSiHay(errores);
        segmentPriceRepository.deleteByRouteId(routeId);
        List<SegmentPrice> nuevos = new ArrayList<>();
        lista.forEach(p -> nuevos.add(new SegmentPrice(UUID.randomUUID(), routeId, p.desdeOrden(), p.hastaOrden(),
                p.precio())));
        List<SegmentPriceResponse> guardados = segmentPriceRepository.saveAll(nuevos).stream()
                .map(SegmentPriceResponse::from).toList();
        // El precio de los tramos sale en los resultados de búsqueda cacheados.
        searchCache.evictAll();
        return guardados;
    }

    private Route ruta(UUID routeId, UUID companyScope) {
        return routeRepository.findWithCompany(routeId)
                .filter(r -> companyScope == null || companyScope.equals(r.getCompany().getId()))
                .orElseThrow(() -> ApiException.notFound("ROUTE_NOT_FOUND", "La ruta no existe"));
    }

    // ---------------------------------------------------------------- comunes

    private UUID empresaActiva(UUID solicitada, UUID companyScope) {
        UUID companyId = CompanyService.resolveCompany(solicitada, companyScope);
        companyService.requireActive(companyId);
        return companyId;
    }

    private static void lanzarSiHay(Map<String, Object> errores) {
        if (!errores.isEmpty()) {
            throw invalida(errores);
        }
    }

    private static ApiException invalida(Map<String, Object> errores) {
        return new ApiException(HttpStatus.BAD_REQUEST, ERROR, "La tarifa no es válida", errores);
    }
}
