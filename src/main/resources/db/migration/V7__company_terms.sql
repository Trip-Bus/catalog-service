CREATE TABLE company_terms (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    cuota_mensual NUMERIC(14,2) NOT NULL CHECK (cuota_mensual >= 0),
    comision_porcentaje NUMERIC(5,2) NOT NULL CHECK (comision_porcentaje BETWEEN 0 AND 30),
    vigente_desde DATE NOT NULL,
    creado_por UUID NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, vigente_desde)
);
CREATE INDEX idx_company_terms_vigencia ON company_terms(company_id, vigente_desde DESC);
INSERT INTO company_terms (id, company_id, cuota_mensual, comision_porcentaje, vigente_desde, creado_por)
SELECT gen_random_uuid(), id, 0, 5, (now() AT TIME ZONE 'America/Bogota')::date,
       '00000000-0000-0000-0000-000000000000'::uuid FROM companies;
