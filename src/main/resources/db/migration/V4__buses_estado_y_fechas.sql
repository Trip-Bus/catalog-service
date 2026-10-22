-- US-110/US-111: buses administrables por la empresa (estado y fechas de auditoría).
ALTER TABLE buses
    ADD COLUMN estado          VARCHAR(20) NOT NULL DEFAULT 'ACTIVO',
    ADD COLUMN creado_en       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    ADD COLUMN actualizado_en  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();

CREATE INDEX idx_buses_company ON buses (company_id);
-- BUS_IN_USE: viajes futuros no cancelados de un bus.
CREATE INDEX idx_itineraries_bus_salida ON itineraries (bus_id, fecha_salida);
