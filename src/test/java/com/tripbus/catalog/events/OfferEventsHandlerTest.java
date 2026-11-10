package com.tripbus.catalog.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripbus.catalog.common.events.EventEnvelope;
import com.tripbus.catalog.domain.Itinerary;
import com.tripbus.catalog.repository.ItineraryRepository;
import com.tripbus.catalog.service.SearchCache;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** US-014: los eventos de Flash Offers marcan y desmarcan la oferta de los itinerarios. */
class OfferEventsHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ItineraryRepository repository = mock(ItineraryRepository.class);
    private final SearchCache cache = mock(SearchCache.class);
    private final OfferEventsHandler handler = new OfferEventsHandler(repository, cache);

    @Test
    void atiendeLosEventosDeOfertasYLogin() {
        assertThat(handler.eventTypes()).containsExactlyInAnyOrder("OfferActivated", "OfferClosed", "OfferSoldOut",
                "UserLoggedIn");
    }

    @Test
    void ofertaActivadaMarcaElItinerario() {
        Itinerary itinerario = mock(Itinerary.class);
        UUID itineraryId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        when(repository.findById(itineraryId)).thenReturn(Optional.of(itinerario));
        Instant fin = Instant.parse("2026-11-01T00:00:00Z");

        handler.handle(evento("OfferActivated", mapper.createObjectNode().put("itineraryId", itineraryId.toString())
                .put("offerId", offerId.toString()).put("precioOferta", 49900).put("fin", fin.toString())));

        verify(itinerario).marcarOferta(offerId, new BigDecimal("49900"), fin);
        verify(cache).evictAll();
    }

    @Test
    void ofertaActivadaSinPrecioNiFinOItinerarioDesconocido() {
        Itinerary itinerario = mock(Itinerary.class);
        UUID itineraryId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        when(repository.findById(itineraryId)).thenReturn(Optional.of(itinerario));
        handler.handle(evento("OfferActivated", mapper.createObjectNode().put("itineraryId", itineraryId.toString())
                .put("offerId", offerId.toString())));
        verify(itinerario).marcarOferta(offerId, null, null);

        handler.handle(evento("OfferActivated", mapper.createObjectNode().put("itineraryId", UUID.randomUUID().toString())
                .put("offerId", offerId.toString()).putNull("precioOferta").putNull("fin")));
        verify(repository, org.mockito.Mockito.times(2)).findById(any());
        verify(cache, org.mockito.Mockito.times(2)).evictAll();
    }

    @Test
    void ofertaCerradaOAgotadaDesmarca() {
        Itinerary itinerario = mock(Itinerary.class);
        UUID offerId = UUID.randomUUID();
        when(repository.findByOfertaId(offerId)).thenReturn(List.of(itinerario));

        handler.handle(evento("OfferClosed", mapper.createObjectNode().put("offerId", offerId.toString())));
        handler.handle(evento("OfferSoldOut", mapper.createObjectNode().put("offerId", offerId.toString())));

        verify(itinerario, org.mockito.Mockito.times(2)).desmarcarOferta();
    }

    @Test
    void loginYTiposDesconocidosNoTocanItinerarios() {
        handler.handle(evento("UserLoggedIn", mapper.createObjectNode().put("userId", "u1")));
        handler.handle(evento("Otro", mapper.createObjectNode()));
        verifyNoInteractions(repository);
        verify(cache, never()).evictAll();
    }

    private static EventEnvelope evento(String tipo, ObjectNode payload) {
        return new EventEnvelope(UUID.randomUUID().toString(), tipo, "flash-offers", Instant.now(), payload);
    }
}
