-- FR-UC-05 y FR-UC-22: el propietario paga la cotización aceptada en una pasarela de pruebas. El
-- cobro nace PENDING al aceptar la cotización, con una referencia única; la pasarela valida
-- usuario, referencia y valor. El dinero del técnico solo se libera cuando el trabajo terminó y el
-- cobro quedó APPROVED.

CREATE TABLE owner_payments (
    id UUID PRIMARY KEY,
    quotation_id UUID NOT NULL REFERENCES quotations(id),
    request_id UUID NOT NULL REFERENCES repair_requests(id),
    property_id UUID REFERENCES properties(id),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    manager_user_id UUID REFERENCES users(id),
    fixer_user_id UUID NOT NULL REFERENCES users(id),
    specialty VARCHAR(32),
    concept VARCHAR(150),
    amount BIGINT NOT NULL,
    reference VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    method VARCHAR(24),
    gateway_transaction_id VARCHAR(64),
    failure_reason VARCHAR(300),
    attempts INTEGER NOT NULL DEFAULT 0,
    paid_by_user_id UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_owner_payment_quotation UNIQUE (quotation_id),
    CONSTRAINT uq_owner_payment_reference UNIQUE (reference),
    CONSTRAINT ck_owner_payment_status CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED')),
    CONSTRAINT ck_owner_payment_method CHECK (method IS NULL OR method IN ('SANDBOX_CARD', 'SANDBOX_PSE', 'SANDBOX_NEQUI')),
    CONSTRAINT ck_owner_payment_amount CHECK (amount > 0 AND amount <= 9007199254740991),
    CONSTRAINT ck_owner_payment_approval CHECK (
        (status = 'APPROVED' AND approved_at IS NOT NULL AND gateway_transaction_id IS NOT NULL)
        OR (status <> 'APPROVED' AND approved_at IS NULL)
    )
);

CREATE INDEX ix_owner_payments_owner ON owner_payments (owner_user_id, created_at DESC);
CREATE INDEX ix_owner_payments_manager ON owner_payments (manager_user_id, created_at DESC);
CREATE INDEX ix_owner_payments_report ON owner_payments (status, approved_at, property_id);

ALTER TABLE fixer_earnings ADD COLUMN funded_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE fixer_earnings ADD COLUMN job_completed_at TIMESTAMP WITH TIME ZONE;
