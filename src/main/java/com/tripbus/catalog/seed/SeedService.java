package com.tripbus.catalog.seed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.catalog.domain.Bus;
import com.tripbus.catalog.domain.BusType;
import com.tripbus.catalog.domain.Company;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.domain.Route;
import com.tripbus.catalog.domain.SeatMap;
import com.tripbus.catalog.domain.Stop;
import com.tripbus.catalog.repository.BusRepository;
import com.tripbus.catalog.repository.CompanyRepository;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.repository.RouteRepository;
import com.tripbus.catalog.repository.SeatMapRepository;
import com.tripbus.catalog.repository.StopRepository;
import com.tripbus.catalog.service.CatalogTime;
import com.tripbus.catalog.service.ItineraryPublisher;
import com.tripbus.catalog.service.SearchCache;
import com.tripbus.catalog.service.SeatLayoutFactory;
import com.tripbus.catalog.web.dto.SeedResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * HT-01: carga idempotente de datos base. Todos los IDs son deterministas (UUID v3 a partir
 * de una llave lógica), así que ejecutar el seed N veces nunca duplica datos: solo agrega
 * los itinerarios de los días nuevos de la ventana [ayer, hoy + 21 días].
 */
@Service
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    private final CompanyRepository companyRepository;
    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final BusRepository busRepository;
    private final SeatMapRepository seatMapRepository;
    private final ItineraryRepository itineraryRepository;
    private final ItineraryPublisher itineraryPublisher;
    private final SearchCache searchCache;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration busTurnaround;

    public SeedService(
            CompanyRepository companyRepository,
            RouteRepository routeRepository,
            StopRepository stopRepository,
            BusRepository busRepository,
            SeatMapRepository seatMapRepository,
            ItineraryRepository itineraryRepository,
            ItineraryPublisher itineraryPublisher,
            SearchCache searchCache,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${tripbus.catalog.bus-turnaround:30m}") Duration busTurnaround) {
        this.busTurnaround = busTurnaround;
        this.companyRepository = companyRepository;
        this.routeRepository = routeRepository;
        this.stopRepository = stopRepository;
        this.busRepository = busRepository;
        this.seatMapRepository = seatMapRepository;
        this.itineraryRepository = itineraryRepository;
        this.itineraryPublisher = itineraryPublisher;
        this.searchCache = searchCache;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public static UUID id(String logicalKey) {
        return UUID.nameUUIDFromBytes(("tripbus:" + logicalKey).getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public SeedResult seed() {
        Map<String, Company> companies = new HashMap<>();
        for (SeedData.CompanySeed c : SeedData.EMPRESAS) {
            UUID companyId = id("company:" + c.key());
            Company company = companyRepository.findById(companyId)
                    .orElseGet(() -> companyRepository.save(new Company(companyId, c.nombre(), c.nit())));
            companies.put(c.key(), company);
        }

        List<Bus> creados = new ArrayList<>();
        List<Itinerary> nuevos = new ArrayList<>();
        int existentes = 0;
        LocalDate hoy = LocalDate.now(clock.withZone(CatalogTime.ZONE));

        for (int r = 0; r < SeedData.RUTAS.size(); r++) {
            SeedData.RouteSeed rs = SeedData.RUTAS.get(r);
            Company company = companies.get(rs.companyKey());
            UUID routeId = id("route:" + rs.key());
            Route route = routeRepository.findById(routeId).orElseGet(() -> {
                Route created = routeRepository.save(
                        new Route(routeId, company, rs.origen(), rs.destino(), rs.duracionMinutos()));
                for (int orden = 0; orden < rs.paradas().size(); orden++) {
                    SeedData.StopSeed st = rs.paradas().get(orden);
                    stopRepository.save(new Stop(id("stop:" + rs.key() + ":" + orden), routeId, orden + 1,
                            st.nombre(), st.lat(), st.lng(), st.minutosDesdeSalida()));
                }
                return created;
            });

            // Un bus fijo por (ruta, horario). US-117: si la ruta (más el alistamiento) no cabe en 24 h, el bus
            // no alcanza a volver a salir al día siguiente: los días impares van en un bus alterno (V5 movió
            // a ese mismo bus los viajes que ya existían).
            boolean conAlterno = SeedData.necesitaBusAlterno(rs, busTurnaround);
            List<Bus> busesRuta = new ArrayList<>();
            List<Bus> alternos = new ArrayList<>();
            for (int slot = 0; slot < SeedData.SALIDAS.size(); slot++) {
                UUID busId = id("bus:" + rs.key() + ":" + slot);
                String placa = String.format("T%s%03d", rs.key().substring(0, 2).toUpperCase(), r * 10 + slot);
                Bus bus = busDelSeed(busId, company, placa, rs.tipoBus(), creados);
                busesRuta.add(bus);
                alternos.add(conAlterno
                        ? busDelSeed(busAlterno(busId), company, "U" + placa.substring(1), rs.tipoBus(), creados)
                        : bus);
            }

            Set<UUID> candidatos = new HashSet<>();
            Map<UUID, Itinerary> porCrear = new LinkedHashMap<>();
            for (int d = -SeedData.DIAS_ATRAS; d <= SeedData.DIAS_ADELANTE; d++) {
                LocalDate fecha = hoy.plusDays(d);
                List<Bus> busesDelDia = SeedData.esDiaImpar(fecha) ? alternos : busesRuta;
                for (int slot = 0; slot < SeedData.SALIDAS.size(); slot++) {
                    LocalTime hora = SeedData.SALIDAS.get(slot);
                    UUID itineraryId = id("itinerary:" + rs.key() + ":" + fecha + ":" + slot);
                    candidatos.add(itineraryId);
                    Instant salida = fecha.atTime(hora).atZone(CatalogTime.ZONE).toInstant();
                    Instant llegada = salida.plusSeconds(rs.duracionMinutos() * 60L);
                    porCrear.put(itineraryId, new Itinerary(itineraryId, route, busesDelDia.get(slot), salida, llegada,
                            precio(rs.precioBase(), hora)));
                }
            }
            Set<UUID> yaExisten = new HashSet<>();
            itineraryRepository.findAllById(candidatos).forEach(i -> yaExisten.add(i.getId()));
            existentes += yaExisten.size();
            for (Map.Entry<UUID, Itinerary> e : porCrear.entrySet()) {
                if (!yaExisten.contains(e.getKey())) {
                    nuevos.add(e.getValue());
                }
            }
        }

        itineraryRepository.saveAll(nuevos);
        itineraryPublisher.publishAfterCommit(nuevos);
        if (!nuevos.isEmpty()) {
            searchCache.evictAll();
        }
        log.info("Seed ejecutado: {} itinerarios nuevos, {} ya existían", nuevos.size(), existentes);
        return new SeedResult(companies.size(), SeedData.RUTAS.size(), creados.size(), nuevos.size(), existentes);
    }

    /** Id del bus que cubre los días impares de una ruta larga (la migración V5 calcula el mismo). */
    public static UUID busAlterno(UUID busId) {
        return id("bus-alterno:" + busId);
    }

    private Bus busDelSeed(UUID busId, Company company, String placa, BusType tipo, List<Bus> creados) {
        return busRepository.findById(busId).orElseGet(() -> {
            Bus bus = busRepository.save(new Bus(busId, company.getId(), placa, tipo, SeatLayoutFactory.capacidad(tipo)));
            seatMapRepository.save(new SeatMap(id("seatmap:" + busId), busId, toJson(SeatLayoutFactory.layout(tipo))));
            creados.add(bus);
            return bus;
        });
    }

    /** Salidas nocturnas (22:00) con 10% de descuento sobre la tarifa base. */
    private static BigDecimal precio(BigDecimal base, LocalTime hora) {
        if (hora.getHour() >= 21) {
            return base.multiply(new BigDecimal("0.90")).setScale(0, RoundingMode.HALF_UP);
        }
        return base;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
