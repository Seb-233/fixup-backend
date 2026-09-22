-- FR-UC-12: publicación de inmuebles sobre el núcleo de propiedades que ya existe (V7__properties).
--
-- Se amplía la tabla en lugar de sustituirla: properties ya está en develop, tiene políticas RLS
-- (V14__properties_rls) y repair_requests la referencia por clave foránea (V15__repair_request_property).
-- Reemplazarla obligaría a recrear ambas cosas sin que ningún caso de uso lo pida.
--
-- Solo se agregan las columnas que el caso de uso ejerce de verdad: el estado de publicación y su
-- reloj, el tipo de inmueble y los datos que la publicación muestra (título, descripción, zona y
-- precio sugerido). Las filas que ya existen quedan en DRAFT y siguen siendo válidas.

ALTER TABLE properties ADD COLUMN status VARCHAR(16) DEFAULT 'DRAFT' NOT NULL;
ALTER TABLE properties ADD COLUMN property_type VARCHAR(16) DEFAULT 'APARTMENT' NOT NULL;
ALTER TABLE properties ADD COLUMN title VARCHAR(150);
ALTER TABLE properties ADD COLUMN description VARCHAR(4000);
ALTER TABLE properties ADD COLUMN zone VARCHAR(100);
ALTER TABLE properties ADD COLUMN monthly_rent_suggestion NUMERIC(14,2);
ALTER TABLE properties ADD COLUMN published_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE properties ADD COLUMN unlisted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE properties ADD CONSTRAINT ck_properties_status CHECK (
    status IN ('DRAFT', 'PUBLISHED', 'UNLISTED')
);

ALTER TABLE properties ADD CONSTRAINT ck_properties_type CHECK (
    property_type IN ('APARTMENT', 'HOUSE', 'STORE', 'OFFICE', 'STUDIO', 'GARAGE', 'LAND')
);

ALTER TABLE properties ADD CONSTRAINT ck_properties_rent_suggestion CHECK (
    monthly_rent_suggestion IS NULL OR monthly_rent_suggestion > 0
);

-- Un inmueble publicado siempre dice desde cuándo lo está y con qué precio y título se publicó; uno
-- retirado conserva la fecha de publicación original además de la de retiro. La regla vive en la
-- base y no solo en Property, para que ninguna ruta futura pueda dejar una fila a medio publicar.
ALTER TABLE properties ADD CONSTRAINT ck_properties_publication CHECK (
    (status = 'DRAFT' AND published_at IS NULL AND unlisted_at IS NULL)
    OR (status = 'PUBLISHED' AND published_at IS NOT NULL
        AND title IS NOT NULL AND zone IS NOT NULL AND monthly_rent_suggestion IS NOT NULL)
    OR (status = 'UNLISTED' AND published_at IS NOT NULL AND unlisted_at IS NOT NULL)
);

CREATE INDEX idx_properties_status_city_zone ON properties(status, city, zone);
