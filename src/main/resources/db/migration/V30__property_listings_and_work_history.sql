-- FR-UC-02: un inmueble publicado es un aviso: operación, precio, tipo, habitaciones, estrato y
-- fotos. Las columnas son opcionales para no invalidar los inmuebles creados antes de este caso.
-- FR-UC-07: el historial de trabajos terminados del inmueble es la evidencia del sello Fix&Rent.
-- FR-UC-11: un administrador inmobiliario puede gestionar inmuebles de terceros.

ALTER TABLE properties ADD COLUMN listing_type VARCHAR(16);
ALTER TABLE properties ADD COLUMN property_type VARCHAR(32);
ALTER TABLE properties ADD COLUMN price BIGINT;
ALTER TABLE properties ADD COLUMN admin_fee BIGINT;
ALTER TABLE properties ADD COLUMN bedrooms INTEGER;
ALTER TABLE properties ADD COLUMN bathrooms INTEGER;
ALTER TABLE properties ADD COLUMN parking_spots INTEGER;
ALTER TABLE properties ADD COLUMN stratum INTEGER;
ALTER TABLE properties ADD COLUMN neighborhood VARCHAR(120);
ALTER TABLE properties ADD COLUMN description VARCHAR(4000);
ALTER TABLE properties ADD COLUMN manager_user_id UUID;

ALTER TABLE properties ADD CONSTRAINT fk_properties_manager FOREIGN KEY (manager_user_id) REFERENCES users(id);
ALTER TABLE properties ADD CONSTRAINT ck_properties_listing_type
    CHECK (listing_type IS NULL OR listing_type IN ('RENT', 'SALE'));
ALTER TABLE properties ADD CONSTRAINT ck_properties_property_type
    CHECK (property_type IS NULL OR property_type IN ('APARTMENT', 'HOUSE', 'STUDIO', 'OFFICE', 'COMMERCIAL', 'COUNTRY_HOUSE'));
ALTER TABLE properties ADD CONSTRAINT ck_properties_price
    CHECK (price IS NULL OR (price > 0 AND price <= 9007199254740991));
ALTER TABLE properties ADD CONSTRAINT ck_properties_admin_fee
    CHECK (admin_fee IS NULL OR (admin_fee >= 0 AND admin_fee <= 9007199254740991));
ALTER TABLE properties ADD CONSTRAINT ck_properties_rooms
    CHECK ((bedrooms IS NULL OR bedrooms BETWEEN 0 AND 20)
        AND (bathrooms IS NULL OR bathrooms BETWEEN 0 AND 20)
        AND (parking_spots IS NULL OR parking_spots BETWEEN 0 AND 20));
ALTER TABLE properties ADD CONSTRAINT ck_properties_stratum
    CHECK (stratum IS NULL OR stratum BETWEEN 1 AND 6);

CREATE INDEX ix_properties_manager ON properties (manager_user_id);
CREATE INDEX ix_properties_catalog ON properties (status, city, listing_type, price);

ALTER TABLE media_assets DROP CONSTRAINT ck_media_purpose;
ALTER TABLE media_assets ADD CONSTRAINT ck_media_purpose CHECK (purpose IN (
    'FIXER_PORTFOLIO', 'REPAIR_REQUEST', 'FIXER_VERIFICATION', 'PROPERTY_PHOTO', 'JOB_EVIDENCE'));

CREATE TABLE property_photos (
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    photo_order INTEGER NOT NULL,
    media_id UUID NOT NULL REFERENCES media_assets(id),
    PRIMARY KEY (property_id, photo_order),
    CONSTRAINT uq_property_photos_media UNIQUE (media_id),
    CONSTRAINT ck_property_photo_order CHECK (photo_order >= 0 AND photo_order < 12)
);

-- Una fila por trabajo terminado en el inmueble. jobs la escribe a través de properties.api al
-- cerrar el trabajo; ratings le agrega la calificación cuando el cliente califica.
CREATE TABLE property_work_history (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    job_id UUID NOT NULL,
    request_id UUID NOT NULL,
    specialty VARCHAR(32) NOT NULL,
    title VARCHAR(150) NOT NULL,
    fixer_user_id UUID NOT NULL REFERENCES users(id),
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    rating INTEGER,
    CONSTRAINT uq_property_work_history_job UNIQUE (job_id),
    CONSTRAINT ck_property_work_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5)
);

CREATE INDEX ix_property_work_history_property ON property_work_history (property_id, completed_at DESC);
