-- FR-UC-16: flujo de revisión de la verificación del Fixer.
-- Owned by fixers. No JPA association exposes another module's entity.

ALTER TABLE fixer_profiles ADD COLUMN submitted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE fixer_profiles ADD COLUMN decided_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE fixer_profiles ADD COLUMN decided_by UUID;
ALTER TABLE fixer_profiles ADD COLUMN rejection_reason VARCHAR(500);

ALTER TABLE fixer_profiles ADD CONSTRAINT ck_fixer_decision_pair
    CHECK ((decided_at IS NULL AND decided_by IS NULL) OR (decided_at IS NOT NULL AND decided_by IS NOT NULL));

ALTER TABLE fixer_profiles ADD CONSTRAINT ck_fixer_rejection_reason
    CHECK (rejection_reason IS NULL OR length(trim(rejection_reason)) > 0);

CREATE TABLE fixer_verification_documents (
    user_id UUID NOT NULL REFERENCES fixer_profiles(user_id) ON DELETE CASCADE,
    document_type VARCHAR(32) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, document_type),
    CONSTRAINT ck_fixer_document_type CHECK (
        document_type IN ('ID_CARD', 'TRADE_CERTIFICATE', 'BACKGROUND_CHECK', 'INSURANCE')
    ),
    CONSTRAINT ck_fixer_document_key CHECK (length(trim(storage_key)) > 0)
);
