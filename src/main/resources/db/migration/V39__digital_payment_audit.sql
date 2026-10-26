-- Metadata only: RepairPayment remains the sole digital financial aggregate.
CREATE TABLE repair_payment_contexts (
    quotation_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    property_id UUID,
    owner_user_id UUID NOT NULL,
    fixer_user_id UUID NOT NULL,
    manager_user_id UUID,
    specialty VARCHAR(32),
    concept VARCHAR(150),
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source VARCHAR(32) NOT NULL CHECK (source IN ('ACCEPTANCE', 'LEGACY_REQUEST_CONTEXT'))
);
CREATE INDEX ix_payment_context_property ON repair_payment_contexts(property_id);

ALTER TABLE repair_payments ADD COLUMN reference VARCHAR(36);
UPDATE repair_payments SET reference = 'FXR-' || UPPER(REPLACE(CAST(id AS VARCHAR(36)), '-', ''));
ALTER TABLE repair_payments ALTER COLUMN reference SET NOT NULL;
ALTER TABLE repair_payments ADD CONSTRAINT uq_repair_payment_reference UNIQUE(reference);
ALTER TABLE repair_payments ADD CONSTRAINT ck_repair_payment_reference CHECK (
    reference = 'FXR-' || UPPER(REPLACE(CAST(id AS VARCHAR(36)), '-', '')));

ALTER TABLE simulated_payment_attempts ADD COLUMN operation_key UUID;
ALTER TABLE simulated_payment_attempts ADD COLUMN payment_reference VARCHAR(36);
ALTER TABLE simulated_payment_attempts ADD COLUMN amount BIGINT;
ALTER TABLE simulated_payment_attempts ADD COLUMN instrument VARCHAR(24);
ALTER TABLE simulated_payment_attempts ADD COLUMN scenario VARCHAR(32);
ALTER TABLE simulated_payment_attempts ADD COLUMN gateway_transaction_id VARCHAR(64);
ALTER TABLE simulated_payment_attempts ADD COLUMN failure_reason VARCHAR(300);
ALTER TABLE simulated_payment_attempts ADD CONSTRAINT uq_payment_operation UNIQUE(payment_id, operation_key);
ALTER TABLE simulated_payment_attempts ADD CONSTRAINT uq_gateway_transaction UNIQUE(gateway_transaction_id);
ALTER TABLE simulated_payment_attempts ADD CONSTRAINT ck_attempt_enrichment CHECK (
    (operation_key IS NULL AND payment_reference IS NULL AND amount IS NULL AND instrument IS NULL
        AND scenario IS NULL AND gateway_transaction_id IS NULL AND failure_reason IS NULL)
    OR (operation_key IS NOT NULL AND payment_reference IS NOT NULL AND amount > 0 AND instrument IS NOT NULL
        AND instrument IN ('SANDBOX_CARD', 'SANDBOX_PSE', 'SANDBOX_NEQUI') AND scenario IS NOT NULL
        AND gateway_transaction_id IS NOT NULL)
);

-- Only recover context when links actually exist. Missing rows stay explicitly incomplete.
-- captured_at is the migration time, not a fabricated historical acceptance date.
INSERT INTO repair_payment_contexts
    (quotation_id, request_id, property_id, owner_user_id, fixer_user_id, manager_user_id,
     specialty, concept, captured_at, source)
SELECT q.id, r.id, r.property_id, r.owner_user_id, q.fixer_user_id, r.manager_user_id,
       r.specialty, r.title, CURRENT_TIMESTAMP, 'LEGACY_REQUEST_CONTEXT'
FROM quotations q JOIN repair_requests r ON r.id = q.request_id
WHERE q.status = 'ACCEPTED' AND (
    EXISTS (SELECT 1 FROM repair_payments p WHERE p.quotation_id = q.id)
    OR EXISTS (SELECT 1 FROM cash_settlements s WHERE s.quotation_id = q.id)
    OR EXISTS (SELECT 1 FROM owner_debts d WHERE d.quotation_id = q.id)
);
