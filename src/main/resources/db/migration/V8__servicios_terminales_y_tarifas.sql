-- Sprint 6: servicios del bus (US-113), terminales (US-115), categorías de asiento (US-141) y tarifas (US-138).

-- US-113: servicios a bordo, separados por comas en el orden del enum ServicioBus (vacío = ninguno).
ALTER TABLE buses ADD COLUMN servicios VARCHAR(200) NOT NULL DEFAULT '';

-- US-115: terminales de transporte. ciudad_norm/nombre_norm: minúsculas y sin tildes (autocompletado).
CREATE TABLE terminals (
    id           UUID PRIMARY KEY,
    ciudad       VARCHAR(80) NOT NULL,
    ciudad_norm  VARCHAR(80) NOT NULL,
    nombre       VARCHAR(120) NOT NULL,
    nombre_norm  VARCHAR(120) NOT NULL,
    direccion    VARCHAR(200),
    lat          DOUBLE PRECISION NOT NULL,
    lng          DOUBLE PRECISION NOT NULL,
    CONSTRAINT uq_terminals_ciudad_nombre UNIQUE (ciudad_norm, nombre_norm)
);
CREATE INDEX idx_terminals_nombre_norm ON terminals (nombre_norm varchar_pattern_ops);
CREATE INDEX idx_terminals_ciudad_norm ON terminals (ciudad_norm varchar_pattern_ops);

-- Terminales principales de las ciudades del catálogo (ids fijos: el seed y las pruebas los conocen).
INSERT INTO terminals (id, ciudad, ciudad_norm, nombre, nombre_norm, direccion, lat, lng) VALUES
('7e0a0001-0000-4000-8000-000000000001', 'Bogotá', 'bogota', 'Terminal Salitre', 'terminal salitre',
 'Diagonal 23 # 69-60', 4.6585, -74.1153),
('7e0a0001-0000-4000-8000-000000000002', 'Bogotá', 'bogota', 'Terminal del Sur', 'terminal del sur',
 'Autopista Sur # 57Q-72', 4.5938, -74.1677),
('7e0a0001-0000-4000-8000-000000000003', 'Bogotá', 'bogota', 'Terminal del Norte', 'terminal del norte',
 'Autopista Norte # 174-50', 4.7562, -74.0447),
('7e0a0001-0000-4000-8000-000000000004', 'Medellín', 'medellin', 'Terminal del Norte', 'terminal del norte',
 'Carrera 64C # 78-580', 6.2789, -75.5703),
('7e0a0001-0000-4000-8000-000000000005', 'Medellín', 'medellin', 'Terminal del Sur', 'terminal del sur',
 'Carrera 65 # 8B-91', 6.2160, -75.5871),
('7e0a0001-0000-4000-8000-000000000006', 'Cali', 'cali', 'Terminal de Transportes de Cali',
 'terminal de transportes de cali', 'Calle 30N # 2AN-29', 3.4706, -76.5267),
('7e0a0001-0000-4000-8000-000000000007', 'Cartagena', 'cartagena', 'Terminal de Transportes de Cartagena',
 'terminal de transportes de cartagena', 'Diagonal 57 # 24-59', 10.3910, -75.4794),
('7e0a0001-0000-4000-8000-000000000008', 'Bucaramanga', 'bucaramanga', 'Terminal de Transportes de Bucaramanga',
 'terminal de transportes de bucaramanga', 'Transversal Metropolitana', 7.1193, -73.1227),
('7e0a0001-0000-4000-8000-000000000009', 'Tunja', 'tunja', 'Terminal de Transportes de Tunja',
 'terminal de transportes de tunja', 'Avenida Oriental # 6-80', 5.5353, -73.3678),
('7e0a0001-0000-4000-8000-000000000010', 'Villavicencio', 'villavicencio', 'Terminal de Transportes de Villavicencio',
 'terminal de transportes de villavicencio', 'Calle 15 # 20-04', 4.1420, -73.6266);

-- US-115: una parada puede ser una terminal. US-138: nombre normalizado para buscar tramos intermedios.
ALTER TABLE stops
    ADD COLUMN terminal_id UUID REFERENCES terminals (id),
    ADD COLUMN nombre_norm VARCHAR(120);
UPDATE stops
SET nombre_norm = regexp_replace(lower(translate(trim(nombre), 'ÁÉÍÓÚÜÑáéíóúüñ', 'AEIOUUNaeiouun')), '\s+', ' ', 'g');
ALTER TABLE stops ALTER COLUMN nombre_norm SET NOT NULL;
CREATE INDEX idx_stops_nombre_norm ON stops (nombre_norm);
CREATE INDEX idx_stops_terminal ON stops (terminal_id);

-- US-141: recargo en COP por categoría de asiento, por empresa (ESTANDAR, PREFERENCIAL o PREMIUM).
CREATE TABLE seat_category_surcharges (
    id          UUID PRIMARY KEY,
    company_id  UUID NOT NULL REFERENCES companies (id),
    categoria   VARCHAR(20) NOT NULL,
    recargo     NUMERIC(12, 2) NOT NULL CHECK (recargo >= 0),
    CONSTRAINT uq_seat_surcharge UNIQUE (company_id, categoria)
);

-- US-138: temporadas (multiplicador del precio entre 0.5 y 3), por empresa y opcionalmente por ruta.
CREATE TABLE fare_seasons (
    id             UUID PRIMARY KEY,
    company_id     UUID NOT NULL REFERENCES companies (id),
    route_id       UUID REFERENCES routes (id),
    nombre         VARCHAR(80) NOT NULL,
    desde          DATE NOT NULL,
    hasta          DATE NOT NULL,
    multiplicador  NUMERIC(4, 2) NOT NULL CHECK (multiplicador BETWEEN 0.5 AND 3),
    creado_en      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CHECK (desde <= hasta)
);
CREATE INDEX idx_fare_seasons_company ON fare_seasons (company_id, desde, hasta);

-- US-138: descuento por tipo de pasajero (ADULTO siempre 0 %).
CREATE TABLE fare_passenger_types (
    id                    UUID PRIMARY KEY,
    company_id            UUID NOT NULL REFERENCES companies (id),
    tipo                  VARCHAR(20) NOT NULL,
    descuento_porcentaje  NUMERIC(5, 2) NOT NULL CHECK (descuento_porcentaje BETWEEN 0 AND 100),
    CONSTRAINT uq_fare_passenger_type UNIQUE (company_id, tipo)
);

-- US-138: precio exacto de un tramo (de la parada desde_orden a la hasta_orden) de una ruta.
CREATE TABLE segment_prices (
    id           UUID PRIMARY KEY,
    route_id     UUID NOT NULL REFERENCES routes (id),
    desde_orden  INTEGER NOT NULL,
    hasta_orden  INTEGER NOT NULL,
    precio       NUMERIC(12, 2) NOT NULL CHECK (precio > 0),
    CONSTRAINT uq_segment_price UNIQUE (route_id, desde_orden, hasta_orden),
    CHECK (desde_orden < hasta_orden)
);
