CREATE TABLE properties (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(255) NOT NULL,
    city VARCHAR(255) NOT NULL,
    area_m2 NUMERIC(10,2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT properties_area_check CHECK (area_m2 > 0),
    CONSTRAINT fk_properties_owner FOREIGN KEY (owner_user_id) REFERENCES users(id)
);

CREATE INDEX idx_properties_owner_user_id ON properties(owner_user_id);
