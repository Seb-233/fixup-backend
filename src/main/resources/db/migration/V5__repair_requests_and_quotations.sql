-- FR-UC-18: solicitudes de reparación y las cotizaciones que los Fixers envían sobre ellas.
-- repair_requests y repair_request_photos pertenecen al módulo requests; quotations al módulo
-- quotations. Ninguna asociación JPA expone la entidad de otro módulo.

CREATE TABLE repair_requests (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id),
    specialty VARCHAR(32) NOT NULL,
    title VARCHAR(150) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    assigned_fixer_user_id UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_request_specialty CHECK (
        specialty IN ('PLUMBING', 'ELECTRICAL', 'PAINTING', 'CARPENTRY', 'MASONRY', 'GENERAL')
    ),
    CONSTRAINT ck_request_status CHECK (status IN ('OPEN', 'ASSIGNED')),
    CONSTRAINT ck_request_title CHECK (length(trim(title)) > 0),
    CONSTRAINT ck_request_description CHECK (length(trim(description)) > 0),
    -- An assigned request always names its fixer, and an open one never does.
    CONSTRAINT ck_request_assignment CHECK (
        (status = 'OPEN' AND assigned_fixer_user_id IS NULL)
        OR (status = 'ASSIGNED' AND assigned_fixer_user_id IS NOT NULL)
    ),
    CONSTRAINT ck_request_not_self_assigned CHECK (
        assigned_fixer_user_id IS NULL OR assigned_fixer_user_id <> owner_user_id
    )
);

CREATE INDEX ix_repair_requests_offer ON repair_requests (status, specialty, created_at DESC);
CREATE INDEX ix_repair_requests_owner ON repair_requests (owner_user_id, created_at DESC);

CREATE TABLE repair_request_photos (
    request_id UUID NOT NULL REFERENCES repair_requests(id) ON DELETE CASCADE,
    photo_order INTEGER NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    PRIMARY KEY (request_id, photo_order),
    CONSTRAINT ck_request_photo_key CHECK (length(trim(storage_key)) > 0),
    CONSTRAINT ck_request_photo_order CHECK (photo_order >= 0 AND photo_order < 6)
);

CREATE TABLE quotations (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES repair_requests(id),
    fixer_user_id UUID NOT NULL REFERENCES users(id),
    amount BIGINT NOT NULL,
    estimated_days INTEGER NOT NULL,
    message VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- One offer per fixer per request: a second attempt is a conflict, not a duplicate row.
    CONSTRAINT uq_quotation_request_fixer UNIQUE (request_id, fixer_user_id),
    CONSTRAINT ck_quotation_status CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_quotation_amount CHECK (amount > 0),
    CONSTRAINT ck_quotation_estimate CHECK (estimated_days BETWEEN 1 AND 365),
    CONSTRAINT ck_quotation_message CHECK (message IS NULL OR length(trim(message)) > 0)
);

CREATE INDEX ix_quotations_request ON quotations (request_id, amount);
CREATE INDEX ix_quotations_fixer ON quotations (fixer_user_id, created_at DESC);

-- Especialidades persistidas para cada Fixer (módulo fixers).
-- Nota: se extiende esta migración provisionalmente en feature/fr-uc-18-quotations;
-- su renumeración definitiva se realizará tras integrar los PR #26 y #27.
CREATE TABLE fixer_specialties (
    fixer_user_id UUID NOT NULL REFERENCES fixer_profiles(user_id) ON DELETE CASCADE,
    specialty VARCHAR(50) NOT NULL,
    PRIMARY KEY (fixer_user_id, specialty),
    CONSTRAINT ck_fixer_specialty CHECK (
        specialty IN ('PLUMBING', 'ELECTRICAL', 'PAINTING', 'CARPENTRY', 'MASONRY', 'GENERAL')
    )
);

CREATE INDEX ix_fixer_specialties_specialty ON fixer_specialties (specialty);
