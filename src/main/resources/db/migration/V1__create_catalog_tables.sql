-- Catálogo de TripBus (esquema propio del servicio).

CREATE TABLE companies (
    id      UUID PRIMARY KEY,
    nombre  VARCHAR(120) NOT NULL,
    nit     VARCHAR(30)
);

CREATE TABLE routes (
    id                UUID PRIMARY KEY,
    company_id        UUID NOT NULL REFERENCES companies (id),
    origen            VARCHAR(80) NOT NULL,
    destino           VARCHAR(80) NOT NULL,
    origen_norm       VARCHAR(80) NOT NULL,
    destino_norm      VARCHAR(80) NOT NULL,
    duracion_minutos  INTEGER NOT NULL,
    estado            VARCHAR(20) NOT NULL DEFAULT 'ACTIVA'
);

-- "Índice de búsqueda" del MVP: búsqueda por origen/destino normalizados (sin tildes, minúsculas).
CREATE INDEX idx_routes_origen_destino ON routes (origen_norm, destino_norm);

CREATE TABLE stops (
    id                    UUID PRIMARY KEY,
    route_id              UUID NOT NULL REFERENCES routes (id),
    orden                 INTEGER NOT NULL,
    nombre                VARCHAR(120) NOT NULL,
    lat                   DOUBLE PRECISION NOT NULL,
    lng                   DOUBLE PRECISION NOT NULL,
    minutos_desde_salida  INTEGER NOT NULL,
    CONSTRAINT uq_stops_route_orden UNIQUE (route_id, orden)
);

CREATE TABLE buses (
    id          UUID PRIMARY KEY,
    company_id  UUID NOT NULL REFERENCES companies (id),
    placa       VARCHAR(10) NOT NULL UNIQUE,
    tipo        VARCHAR(20) NOT NULL,
    capacidad   INTEGER NOT NULL
);

CREATE TABLE seat_maps (
    id            UUID PRIMARY KEY,
    bus_id        UUID NOT NULL UNIQUE REFERENCES buses (id),
    distribucion  TEXT NOT NULL
);

CREATE TABLE itineraries (
    id                      UUID PRIMARY KEY,
    route_id                UUID NOT NULL REFERENCES routes (id),
    bus_id                  UUID NOT NULL REFERENCES buses (id),
    fecha_salida            TIMESTAMP WITH TIME ZONE NOT NULL,
    fecha_llegada_estimada  TIMESTAMP WITH TIME ZONE NOT NULL,
    estado                  VARCHAR(20) NOT NULL DEFAULT 'PROGRAMADO',
    precio_base             NUMERIC(12, 2) NOT NULL,
    moneda                  VARCHAR(3) NOT NULL DEFAULT 'COP',
    oferta_id               UUID,
    precio_oferta           NUMERIC(12, 2),
    oferta_fin              TIMESTAMP WITH TIME ZONE,
    creado_en               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_itineraries_route_salida ON itineraries (route_id, fecha_salida);
CREATE INDEX idx_itineraries_oferta ON itineraries (oferta_id);

CREATE TABLE processed_events (
    event_id      VARCHAR(64) PRIMARY KEY,
    event_type    VARCHAR(80) NOT NULL,
    processed_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
