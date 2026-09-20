-- FR-UC-20: trabajos e ingresos del técnico.
-- jobs pertenece al módulo jobs; fixer_earnings y payouts al módulo payments.
-- Ninguna asociación JPA expone la entidad de otro módulo.

CREATE TABLE jobs (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES repair_requests(id),
    quotation_id UUID NOT NULL REFERENCES quotations(id),
    fixer_user_id UUID NOT NULL REFERENCES users(id),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Una cotización aceptada abre un solo trabajo, aunque el evento llegue repetido.
    CONSTRAINT uq_job_quotation UNIQUE (quotation_id),
    CONSTRAINT ck_job_status CHECK (status IN ('ASSIGNED', 'COMPLETED')),
    CONSTRAINT ck_job_completion CHECK (
        (status = 'ASSIGNED' AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_job_parties CHECK (fixer_user_id <> owner_user_id)
);

CREATE INDEX ix_jobs_fixer ON jobs (fixer_user_id, created_at DESC);

CREATE TABLE fixer_earnings (
    id UUID PRIMARY KEY,
    quotation_id UUID NOT NULL REFERENCES quotations(id),
    fixer_user_id UUID NOT NULL REFERENCES users(id),
    gross_amount BIGINT NOT NULL,
    commission_amount BIGINT NOT NULL,
    net_amount BIGINT NOT NULL,
    -- La tarifa se copia al crear el ingreso: cambiarla mañana no reescribe lo ya liquidado.
    commission_rate_bps INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    released_at TIMESTAMP WITH TIME ZONE,
    paid_out_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_earning_quotation UNIQUE (quotation_id),
    CONSTRAINT ck_earning_status CHECK (status IN ('HELD', 'AVAILABLE', 'PAID_OUT')),
    -- La base garantiza que la plata cuadra, no solo el código.
    CONSTRAINT ck_earning_amounts CHECK (
        gross_amount > 0 AND commission_amount >= 0 AND net_amount >= 0
        AND commission_amount + net_amount = gross_amount
    ),
    CONSTRAINT ck_earning_rate CHECK (commission_rate_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_earning_release CHECK (status = 'HELD' OR released_at IS NOT NULL),
    CONSTRAINT ck_earning_payout CHECK (status <> 'PAID_OUT' OR paid_out_at IS NOT NULL)
);

CREATE INDEX ix_fixer_earnings_balance ON fixer_earnings (fixer_user_id, status);

CREATE TABLE payouts (
    id UUID PRIMARY KEY,
    fixer_user_id UUID NOT NULL REFERENCES users(id),
    amount BIGINT NOT NULL,
    earning_count INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_payout_status CHECK (status IN ('REQUESTED')),
    CONSTRAINT ck_payout_amount CHECK (amount > 0),
    CONSTRAINT ck_payout_count CHECK (earning_count > 0)
);

CREATE INDEX ix_payouts_fixer ON payouts (fixer_user_id, requested_at DESC);
