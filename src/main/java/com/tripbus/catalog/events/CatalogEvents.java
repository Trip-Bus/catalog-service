package com.tripbus.catalog.events;

/** Tipos de evento que publica / consume Catalog. */
public final class CatalogEvents {

    public static final String ITINERARY_PUBLISHED = "ItineraryPublished";
    public static final String ITINERARY_CANCELLED = "ItineraryCancelled";
    public static final String PRICE_CHANGED = "PriceChanged";

    public static final String OFFER_ACTIVATED = "OfferActivated";
    public static final String OFFER_CLOSED = "OfferClosed";
    public static final String OFFER_SOLD_OUT = "OfferSoldOut";
    public static final String USER_LOGGED_IN = "UserLoggedIn";

    private CatalogEvents() {
    }
}
