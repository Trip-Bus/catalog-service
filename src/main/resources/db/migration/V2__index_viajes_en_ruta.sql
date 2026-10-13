-- Viajes en ruta (GET /trips/in-progress): se puede buscar solo por destino y
-- idx_routes_origen_destino no sirve sin origen.
CREATE INDEX idx_routes_destino ON routes (destino_norm);
