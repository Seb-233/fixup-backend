-- V15__repair_request_property.sql
-- FR-UC-04: Relate repair requests to properties for OWNER authorization boundary (FR-UC-25)
-- Intentionally nullable for legacy historical rows that predate the property core.

ALTER TABLE repair_requests ADD COLUMN property_id UUID;

ALTER TABLE repair_requests ADD CONSTRAINT fk_repair_requests_property FOREIGN KEY (property_id) REFERENCES properties (id);

CREATE INDEX idx_repair_requests_property_id ON repair_requests(property_id);