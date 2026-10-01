package com.tripbus.catalog.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripbus.catalog.common.events.DomainEventHandler;
import com.tripbus.catalog.common.events.EventEnvelope;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.service.SearchCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * US-014: marca/desmarca los itinerarios con oferta relámpago activa a partir de los
 * eventos de Flash Offers. También consume UserLoggedIn (solo auditoría, no crítico).
 */
@Component
public class OfferEventsHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OfferEventsHandler.class);

    private final ItineraryRepository itineraryRepository;
    private final SearchCache searchCache;

    public OfferEventsHandler(ItineraryRepository itineraryRepository, SearchCache searchCache) {
        this.itineraryRepository = itineraryRepository;
        this.searchCache = searchCache;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(CatalogEvents.OFFER_ACTIVATED, CatalogEvents.OFFER_CLOSED, CatalogEvents.OFFER_SOLD_OUT,
                CatalogEvents.USER_LOGGED_IN);
    }

    @Override
    public void handle(EventEnvelope event) {
        JsonNode p = event.payload();
        switch (event.type()) {
            case CatalogEvents.OFFER_ACTIVATED -> {
                UUID itineraryId = UUID.fromString(p.path("itineraryId").asText());
                UUID offerId = UUID.fromString(p.path("offerId").asText());
                BigDecimal precio = p.hasNonNull("precioOferta") ? p.get("precioOferta").decimalValue() : null;
                Instant fin = p.hasNonNull("fin") ? Instant.parse(p.get("fin").asText()) : null;
                itineraryRepository.findById(itineraryId).ifPresent(i -> i.marcarOferta(offerId, precio, fin));
                searchCache.evictAll();
            }
            case CatalogEvents.OFFER_CLOSED, CatalogEvents.OFFER_SOLD_OUT -> {
                UUID offerId = UUID.fromString(p.path("offerId").asText());
                itineraryRepository.findByOfertaId(offerId).forEach(i -> i.desmarcarOferta());
                searchCache.evictAll();
            }
            case CatalogEvents.USER_LOGGED_IN -> log.debug("Auditoría: login del usuario {}", p.path("userId").asText());
            default -> {
            }
        }
    }
}
