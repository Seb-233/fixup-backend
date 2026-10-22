-- FR-UC-08: el arrendatario con contrato vigente reporta la falla; el propietario sigue siendo
-- quien decide y paga, así que la solicitud distingue a quien la reporta (requested_by).
-- FR-UC-11: el administrador inmobiliario del inmueble gestiona sus solicitudes e invita técnicos.
-- FR-UC-19: una solicitud se cierra cuando su trabajo termina.

ALTER TABLE repair_requests ADD COLUMN requested_by_user_id UUID;
ALTER TABLE repair_requests ADD COLUMN manager_user_id UUID;
ALTER TABLE repair_requests ADD COLUMN property_name VARCHAR(255);
ALTER TABLE repair_requests ADD COLUMN property_address VARCHAR(255);
ALTER TABLE repair_requests ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE repair_requests ADD CONSTRAINT fk_repair_requests_requested_by
    FOREIGN KEY (requested_by_user_id) REFERENCES users(id);
ALTER TABLE repair_requests ADD CONSTRAINT fk_repair_requests_manager
    FOREIGN KEY (manager_user_id) REFERENCES users(id);

UPDATE repair_requests SET requested_by_user_id = owner_user_id WHERE requested_by_user_id IS NULL;
UPDATE repair_requests
SET property_name = (SELECT p.name FROM properties p WHERE p.id = repair_requests.property_id),
    property_address = (SELECT p.address FROM properties p WHERE p.id = repair_requests.property_id)
WHERE property_id IS NOT NULL;

CREATE INDEX ix_repair_requests_requested_by ON repair_requests (requested_by_user_id, created_at DESC);
CREATE INDEX ix_repair_requests_manager ON repair_requests (manager_user_id, created_at DESC);
CREATE INDEX ix_repair_requests_assigned_fixer ON repair_requests (assigned_fixer_user_id, status);

CREATE TABLE request_invitations (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES repair_requests(id) ON DELETE CASCADE,
    fixer_user_id UUID NOT NULL REFERENCES users(id),
    invited_by_user_id UUID NOT NULL REFERENCES users(id),
    note VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_request_invitation UNIQUE (request_id, fixer_user_id),
    CONSTRAINT ck_request_invitation_note CHECK (note IS NULL OR length(trim(note)) > 0)
);

CREATE INDEX ix_request_invitations_fixer ON request_invitations (fixer_user_id, created_at DESC);
