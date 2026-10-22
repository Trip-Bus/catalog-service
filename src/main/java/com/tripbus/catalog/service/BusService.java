package com.tripbus.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.catalog.common.error.ApiException;
import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.BusStatus;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.domain.SeatMap;
import com.tripbus.catalog.repository.BusRepository;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.SeatMapRepository;
import com.tripbus.catalog.web.dto.BusRequest;
import com.tripbus.catalog.web.dto.BusResponse;
import com.tripbus.catalog.web.dto.BusUpdateRequest;
import com.tripbus.catalog.web.dto.PageResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * US-110/US-111: buses de una empresa y su mapa de asientos.
 *
 * <p>{@code companyScope} es la empresa del OPERADOR (claim companyId del JWT) o null para el ADMIN.
 * Un bus de otra empresa responde 404, igual que uno que no existe.
 */
@Service
public class BusService {

    private final BusRepository busRepository;
    private final SeatMapRepository seatMapRepository;
    private final ItineraryRepository itineraryRepository;
    private final CompanyService companyService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BusService(
            BusRepository busRepository,
            SeatMapRepository seatMapRepository,
            ItineraryRepository itineraryRepository,
            CompanyService companyService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.busRepository = busRepository;
        this.seatMapRepository = seatMapRepository;
        this.itineraryRepository = itineraryRepository;
        this.companyService = companyService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public BusResponse create(BusRequest request, UUID companyScope) {
        UUID companyId = CompanyService.resolveCompany(request.companyId(), companyScope);
        companyService.requireActive(companyId);
        UUID id = UUID.randomUUID();
        String placa = placa(request.placa());
        validarPlacaLibre(placa, id);
        SeatMapValidator.Resultado mapa = request.distribucion() != null
                ? SeatMapValidator.validar(request.distribucion(), request.tipo())
                : porDefecto(request.tipo());
        Bus bus = busRepository.save(new Bus(id, companyId, placa, request.tipo(), mapa.capacidad()));
        seatMapRepository.save(new SeatMap(UUID.randomUUID(), id, toJson(mapa.distribucion())));
        return BusResponse.from(bus);
    }

    @Transactional(readOnly = true)
    public PageResponse<BusResponse> list(BusStatus estado, BusType tipo, UUID companyId, int page, int size,
                                          UUID companyScope) {
        UUID empresa = companyScope != null ? companyScope : companyId;
        Specification<Bus> spec = (root, query, cb) -> {
            List<Predicate> filtros = new ArrayList<>();
            if (empresa != null) {
                filtros.add(cb.equal(root.get("companyId"), empresa));
            }
            if (estado != null) {
                filtros.add(cb.equal(root.get("estado"), estado));
            }
            if (tipo != null) {
                filtros.add(cb.equal(root.get("tipo"), tipo));
            }
            return cb.and(filtros.toArray(new Predicate[0]));
        };
        return PageResponse.of(busRepository.findAll(spec, PageRequest.of(page, size, Sort.by("placa"))),
                BusResponse::from);
    }

    @Transactional(readOnly = true)
    public BusResponse get(UUID id, UUID companyScope) {
        return BusResponse.from(find(id, companyScope));
    }

    /**
     * Cambia placa y tipo. Si cambia el tipo, el mapa vuelve al layout por defecto del nuevo tipo
     * (cambia la capacidad), así que no se permite si el bus tiene viajes futuros (409 BUS_IN_USE).
     */
    @Transactional
    public BusResponse update(UUID id, BusUpdateRequest request, UUID companyScope) {
        Bus bus = find(id, companyScope);
        String placa = placa(request.placa());
        validarPlacaLibre(placa, id);
        boolean cambiaTipo = bus.getTipo() != request.tipo();
        if (cambiaTipo) {
            validarSinViajesFuturos(bus);
        }
        bus.actualizar(placa, request.tipo(), clock.instant());
        if (cambiaTipo) {
            guardarMapa(bus, porDefecto(request.tipo()));
        }
        return BusResponse.from(bus);
    }

    @Transactional
    public BusResponse changeStatus(UUID id, BusStatus estado, UUID companyScope) {
        Bus bus = find(id, companyScope);
        if (bus.getEstado() != estado) {
            bus.cambiarEstado(estado, clock.instant());
        }
        return BusResponse.from(bus);
    }

    /** US-111: mismo JSON que SeatLayoutFactory (tipo, capacidad, pisos). */
    @Transactional(readOnly = true)
    public JsonNode seatMap(UUID id, UUID companyScope) {
        Bus bus = find(id, companyScope);
        return seatMapRepository.findByBusId(bus.getId())
                .map(s -> readJson(s.getDistribucion()))
                .orElseGet(() -> porDefecto(bus.getTipo()).distribucion());
    }

    @Transactional
    public JsonNode updateSeatMap(UUID id, JsonNode distribucion, UUID companyScope) {
        Bus bus = find(id, companyScope);
        SeatMapValidator.Resultado mapa = SeatMapValidator.validar(distribucion, bus.getTipo());
        validarSinViajesFuturos(bus);
        guardarMapa(bus, mapa);
        return mapa.distribucion();
    }

    private void guardarMapa(Bus bus, SeatMapValidator.Resultado mapa) {
        String json = toJson(mapa.distribucion());
        SeatMap seatMap = seatMapRepository.findByBusId(bus.getId())
                .orElseGet(() -> new SeatMap(UUID.randomUUID(), bus.getId(), json));
        seatMap.cambiarDistribucion(json);
        seatMapRepository.save(seatMap);
        bus.cambiarCapacidad(mapa.capacidad(), clock.instant());
    }

    private Bus find(UUID id, UUID companyScope) {
        return busRepository.findById(id)
                .filter(b -> companyScope == null || companyScope.equals(b.getCompanyId()))
                .orElseThrow(() -> ApiException.notFound("BUS_NOT_FOUND", "El bus no existe"));
    }

    private void validarPlacaLibre(String placa, UUID id) {
        if (busRepository.existsByPlacaAndIdNot(placa, id)) {
            throw ApiException.conflict("PLACA_ALREADY_EXISTS", "Ya existe un bus con la placa " + placa);
        }
    }

    private void validarSinViajesFuturos(Bus bus) {
        if (itineraryRepository.existsFutureForBus(bus.getId(), clock.instant())) {
            throw ApiException.conflict("BUS_IN_USE",
                    "El bus tiene viajes futuros no cancelados; el cambio de bus en un viaje llega en el Sprint 3");
        }
    }

    private SeatMapValidator.Resultado porDefecto(BusType tipo) {
        return SeatMapValidator.validar(objectMapper.valueToTree(SeatLayoutFactory.layout(tipo)), tipo);
    }

    private static String placa(String placa) {
        return placa.trim().toUpperCase(Locale.ROOT);
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Mapa de asientos corrupto", e);
        }
    }
}
