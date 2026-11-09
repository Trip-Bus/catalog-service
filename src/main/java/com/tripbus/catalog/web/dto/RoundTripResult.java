package com.tripbus.catalog.web.dto;

import java.util.List;

/** US-137: viajes de ida (origen → destino en date) y de vuelta (destino → origen en returnDate). */
public record RoundTripResult(List<PublicTripSearchResult> ida, List<PublicTripSearchResult> vuelta) {
}
