ALTER TABLE properties ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE properties ADD COLUMN published_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE properties ADD COLUMN published_by_user_id UUID;

UPDATE properties SET published_at = created_at WHERE published_at IS NULL;
UPDATE properties SET published_by_user_id = owner_user_id WHERE published_by_user_id IS NULL;

ALTER TABLE properties ADD CONSTRAINT fk_properties_publisher FOREIGN KEY (published_by_user_id) REFERENCES users(id);
CREATE INDEX idx_properties_status ON properties(status);
