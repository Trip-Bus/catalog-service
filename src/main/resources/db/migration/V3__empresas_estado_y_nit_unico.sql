-- US-100: empresas transportadoras administrables (estado, fechas de auditoría y NIT único).
ALTER TABLE companies
    ADD COLUMN estado          VARCHAR(20) NOT NULL DEFAULT 'ACTIVA',
    ADD COLUMN creado_en       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    ADD COLUMN actualizado_en  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();

-- NIT único sin importar el dígito de verificación: 900123456 y 900123456-7 son la misma empresa.
CREATE UNIQUE INDEX uq_companies_nit ON companies (split_part(nit, '-', 1));
CREATE INDEX idx_companies_estado ON companies (estado);
