package com.tripbus.catalog.web.dto;

import com.tripbus.catalog.domain.Itinerary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** GET /internal/itineraries: un viaje no cancelado con su ruta, salida, bus, capacidad y precio base. */
public record InternalItinerary(UUID itineraryId, UUID companyId, UUID routeId, String origen, String destino,
                                Instant fechaSalida, Instant fechaLlegadaEstimada, UUID busId, String placa,
                                String tipoBus, int capacidad, BigDecimal precioBase, String moneda, String estado) {

    public static InternalItinerary from(Itinerary i) {
        return new InternalItinerary(i.getId(), i.getRoute().getCompany().getId(), i.getRoute().getId(),
                i.getRoute().getOrigen(), i.getRoute().getDestino(), i.getFechaSalida(), i.getFechaLlegadaEstimada(),
                i.getBus().getId(), i.getBus().getPlaca(), i.getBus().getTipo().name(), i.getBus().getCapacidad(),
                i.getPrecioBase(), i.getMoneda(), i.getEstado().name());
    }
}
