package com.tripbus.catalog.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public record SeatMapResponse(
        UUID itineraryId,
        UUID busId,
        String placa,
        String tipoBus,
        int capacidad,
        List<String> asientos,
        JsonNode distribucion) {
}
