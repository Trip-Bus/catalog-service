-- US-117: un bus nunca puede estar en dos viajes no cancelados que se crucen. El servicio además exige
-- el margen de alistamiento (tripbus.catalog.bus-turnaround); la base garantiza que no haya cruce real.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Datos del seed anterior: en las rutas de más de 23 h 30 min (p. ej. Cartagena-Villavicencio) el mismo bus
-- salía todos los días a la misma hora y sus viajes se cruzaban. Igual que el seed nuevo (SeedService), los
-- días impares (días desde 2000-01-01, hora de Colombia) pasan a un "bus alterno" con el MISMO id que
-- calcula el seed: UUID v3 de 'tripbus:bus-alterno:<busId>' (UUID.nameUUIDFromBytes en Java).
CREATE FUNCTION pg_temp.tripbus_id(nombre text) RETURNS uuid LANGUAGE sql IMMUTABLE AS $$
    SELECT (substr(h, 1, 12) || '3' || substr(h, 14, 3)
            || to_hex((('x' || substr(h, 17, 1))::bit(4)::int & 3) | 8)
            || substr(h, 18, 15))::uuid
    FROM (SELECT md5(nombre) AS h) s
$$;

CREATE TEMP TABLE buses_largos AS
SELECT DISTINCT i.bus_id AS id, pg_temp.tripbus_id('tripbus:bus-alterno:' || i.bus_id) AS alterno
FROM itineraries i
JOIN routes r ON r.id = i.route_id
WHERE r.duracion_minutos + 30 > 24 * 60;

INSERT INTO buses (id, company_id, placa, tipo, capacidad, estado)
SELECT l.alterno, b.company_id, 'U' || substr(b.placa, 2), b.tipo, b.capacidad, b.estado
FROM buses_largos l
JOIN buses b ON b.id = l.id
ON CONFLICT (id) DO NOTHING;

INSERT INTO seat_maps (id, bus_id, distribucion)
SELECT pg_temp.tripbus_id('tripbus:seatmap:' || l.alterno), l.alterno, s.distribucion
FROM buses_largos l
JOIN seat_maps s ON s.bus_id = l.id
ON CONFLICT (bus_id) DO NOTHING;

UPDATE itineraries i
SET bus_id = l.alterno
FROM buses_largos l, routes r
WHERE i.bus_id = l.id
  AND r.id = i.route_id
  AND r.duracion_minutos + 30 > 24 * 60
  AND ((i.fecha_salida AT TIME ZONE 'America/Bogota')::date - DATE '2000-01-01') % 2 = 1;

DROP TABLE buses_largos;

ALTER TABLE itineraries
    ADD CONSTRAINT ex_itineraries_bus_sin_cruce
    EXCLUDE USING gist (bus_id WITH =, tstzrange(fecha_salida, fecha_llegada_estimada) WITH &&)
    WHERE (estado <> 'CANCELADO');

-- US-114: rutas por empresa (GET /routes del operador).
CREATE INDEX idx_routes_company ON routes (company_id);
