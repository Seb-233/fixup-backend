-- FR-UC-23: auditable consent for personal data processing.
-- consentAcceptedAt records when the fixer explicitly accepted the data-processing terms for the
-- first time. It is set once and never overwritten (idempotent): resubmitting documents while
-- consent was already recorded does not change the historical timestamp.
-- consentVersion identifies which version of the terms the fixer accepted, to support future
-- version bumps without losing the original record. Nullable: rows created before this column
-- existed (existing profiles) have no consent on record and will be required to provide it on
-- their next submission.

ALTER TABLE fixer_profiles ADD COLUMN consent_accepted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE fixer_profiles ADD COLUMN consent_version VARCHAR(16);

-- A fixer that has consent_accepted_at must also have consent_version and vice versa.
ALTER TABLE fixer_profiles
    ADD CONSTRAINT ck_fixer_consent_coherent CHECK (
        (consent_accepted_at IS NULL AND consent_version IS NULL) OR
        (consent_accepted_at IS NOT NULL AND consent_version IS NOT NULL)
    );