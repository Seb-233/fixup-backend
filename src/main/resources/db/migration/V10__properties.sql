CREATE TABLE properties (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    manager_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    property_type VARCHAR(20) NOT NULL CHECK (property_type IN (
        'APARTMENT','HOUSE','STORE','OFFICE','STUDIO','GARAGE','LAND')),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','UNLISTED','DELETED')),
    title VARCHAR(150) NOT NULL,
    description VARCHAR(4000),
    address_street VARCHAR(200),
    address_number VARCHAR(30),
    address_floor VARCHAR(20),
    address_apartment VARCHAR(20),
    city VARCHAR(200) NOT NULL,
    zone VARCHAR(100) NOT NULL,
    postal_code VARCHAR(20),
    latitude NUMERIC(10,6),
    longitude NUMERIC(10,6),
    surface_m2 DOUBLE PRECISION,
    covered_surface_m2 DOUBLE PRECISION,
    bedrooms INTEGER,
    bathrooms INTEGER,
    covered_parking_spots INTEGER,
    has_balcony BOOLEAN,
    has_terrace BOOLEAN,
    has_garden BOOLEAN,
    has_elevator BOOLEAN,
    has_pool BOOLEAN,
    has_security BOOLEAN,
    pets_allowed BOOLEAN,
    furnished BOOLEAN,
    monthly_rent_suggestion NUMERIC(14,2),
    monthly_condo_fee NUMERIC(14,2),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    unlisted_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    CHECK (
        CASE status
            WHEN 'PUBLISHED' THEN monthly_rent_suggestion IS NOT NULL AND monthly_rent_suggestion > 0 AND published_at IS NOT NULL
            WHEN 'UNLISTED' THEN published_at IS NOT NULL AND unlisted_at IS NOT NULL
            WHEN 'DELETED' THEN deleted_at IS NOT NULL
            ELSE TRUE
        END
    ),
    CHECK (
        (surface_m2 IS NULL OR surface_m2 > 0)
        AND (covered_surface_m2 IS NULL OR covered_surface_m2 >= 0)
        AND (bedrooms IS NULL OR bedrooms >= 0)
        AND (bathrooms IS NULL OR bathrooms >= 0)
        AND (covered_parking_spots IS NULL OR covered_parking_spots >= 0)
        AND (monthly_rent_suggestion IS NULL OR monthly_rent_suggestion >= 0)
        AND (monthly_condo_fee IS NULL OR monthly_condo_fee >= 0)
    )
);

CREATE INDEX idx_properties_owner ON properties(owner_user_id) WHERE status <> 'DELETED';
CREATE INDEX idx_properties_manager ON properties(manager_user_id) WHERE manager_user_id IS NOT NULL AND status <> 'DELETED';
CREATE INDEX idx_properties_published ON properties(status, city, zone, property_type, monthly_rent_suggestion, bedrooms, published_at DESC) WHERE status = 'PUBLISHED';

CREATE TABLE property_amenities (
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    pos INTEGER NOT NULL,
    amenity VARCHAR(80) NOT NULL,
    PRIMARY KEY (property_id, pos)
);

CREATE TABLE property_media_ids (
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    pos INTEGER NOT NULL,
    media_id UUID NOT NULL,
    PRIMARY KEY (property_id, pos)
);
