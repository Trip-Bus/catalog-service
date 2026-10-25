-- US-118/US-119: historial de cambios de un viaje (retraso, reprogramación, cambio de bus y cancelación).
CREATE TABLE itinerary_changes (
    id               UUID PRIMARY KEY,
    itinerary_id     UUID NOT NULL REFERENCES itineraries (id),
    tipo             VARCHAR(20) NOT NULL,
    salida_anterior  TIMESTAMP WITH TIME ZONE,
    salida_nueva     TIMESTAMP WITH TIME ZONE,
    bus_anterior     UUID,
    bus_nuevo        UUID,
    motivo           VARCHAR(300) NOT NULL,
    hecho_por        UUID,
    creado_en        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_itinerary_changes_itinerary ON itinerary_changes (itinerary_id, creado_en);
