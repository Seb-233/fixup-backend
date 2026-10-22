CREATE TABLE repair_payments (
    id UUID PRIMARY KEY,
    quotation_id UUID NOT NULL,
    request_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    fixer_user_id UUID NOT NULL,
    -- FR-UC-11: el administrador inmobiliario del inmueble delegado, que también puede pagarlo.
    manager_user_id UUID,
    amount BIGINT NOT NULL,
    payment_method VARCHAR(16) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_repair_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_repair_payment_method CHECK (payment_method IN ('TRANSFER', 'CASH')),
    CONSTRAINT ck_repair_payment_purpose CHECK (purpose IN ('SERVICE', 'OUTSTANDING_BALANCE')),
    CONSTRAINT ck_repair_payment_status CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'ERROR')),
    CONSTRAINT ck_repair_payment_completion CHECK (
        (status = 'APPROVED' AND completed_at IS NOT NULL)
        OR (status <> 'APPROVED' AND completed_at IS NULL)
    ),
    CONSTRAINT uq_repair_payment_quotation_purpose UNIQUE (quotation_id, purpose)
);

CREATE INDEX ix_repair_payments_owner ON repair_payments (owner_user_id, created_at DESC);
CREATE INDEX ix_repair_payments_fixer ON repair_payments (fixer_user_id, created_at DESC);
CREATE INDEX ix_repair_payments_manager ON repair_payments (manager_user_id, created_at DESC);
CREATE INDEX ix_repair_payments_request ON repair_payments (request_id);
CREATE INDEX ix_repair_payments_quotation ON repair_payments (quotation_id);
CREATE INDEX ix_repair_payments_status ON repair_payments (status);

CREATE TABLE owner_debts (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    fixer_user_id UUID NOT NULL,
    request_id UUID NOT NULL,
    quotation_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    settled_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_owner_debt_amount CHECK (amount > 0),
    CONSTRAINT ck_owner_debt_status CHECK (status IN ('OUTSTANDING', 'PAID')),
    CONSTRAINT ck_owner_debt_settlement CHECK (
        (status = 'PAID' AND settled_at IS NOT NULL)
        OR (status = 'OUTSTANDING' AND settled_at IS NULL)
    ),
    CONSTRAINT uq_owner_debt_quotation UNIQUE (quotation_id)
);

CREATE INDEX ix_owner_debts_owner ON owner_debts (owner_user_id, status, created_at DESC);
CREATE INDEX ix_owner_debts_fixer ON owner_debts (fixer_user_id, created_at DESC);

CREATE TABLE simulated_payment_attempts (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES repair_payments(id) ON DELETE RESTRICT,
    requested_outcome VARCHAR(16) NOT NULL,
    resulting_status VARCHAR(16) NOT NULL,
    attempted_by_user_id UUID NOT NULL,
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_payment_attempt_requested_outcome CHECK (requested_outcome IN ('PENDING', 'APPROVED', 'DECLINED', 'ERROR')),
    CONSTRAINT ck_payment_attempt_resulting_status CHECK (resulting_status IN ('PENDING', 'APPROVED', 'DECLINED', 'ERROR'))
);
CREATE INDEX ix_simulated_payment_attempts_payment ON simulated_payment_attempts (payment_id, attempted_at);
