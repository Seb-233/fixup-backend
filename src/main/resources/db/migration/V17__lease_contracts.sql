-- FR-UC-14: Tabla de contratos de arrendamiento (lease contracts)
-- Renumerada de V9 a V17 al rebasar sobre develop: V9 ya estaba ocupada por chat_messages.
-- media_ids se guarda como VARCHAR con JSON serializado y no como JSONB: las migraciones de
-- db/migration corren tambien contra H2 en la suite rapida, que no conoce el tipo JSONB.
-- Estado legal: DRAFT -> PENDING_TENANT_SIGNATURE -> PENDING_OWNER_SIGNATURE -> SIGNED/ACTIVE
-- Estados finales: EXPIRED, TERMINATED_BY_OWNER, TERMINATED_BY_TENANT, CANCELLED

CREATE TABLE lease_contracts (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL,
    owner_user_id UUID NOT NULL REFERENCES users(id),
    tenant_user_id UUID NOT NULL REFERENCES users(id),
    real_estate_manager_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    status VARCHAR(40) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    monthly_rent BIGINT NOT NULL,
    security_deposit BIGINT NOT NULL,
    payment_frequency VARCHAR(20) NOT NULL,
    payment_day_of_month INTEGER NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'ARS',
    contract_terms VARCHAR(10000),
    media_ids VARCHAR(4000),
    clauses VARCHAR(10000),
    owner_signed BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_signed BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_signed_at TIMESTAMP WITH TIME ZONE,
    owner_signed_at TIMESTAMP WITH TIME ZONE,
    signed_by_owner_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    signed_by_tenant_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    termination_reason VARCHAR(2000),
    terminated_at TIMESTAMP WITH TIME ZONE,
    terminated_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    terminated_from_status VARCHAR(40),
    cancellation_reason VARCHAR(2000),
    cancelled_at TIMESTAMP WITH TIME ZONE,
    cancelled_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    renewal_start_date DATE,
    renewal_end_date DATE,
    original_contract_id UUID REFERENCES lease_contracts(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_contract_status CHECK (status IN (
        'DRAFT', 'PENDING_TENANT_SIGNATURE', 'PENDING_OWNER_SIGNATURE',
        'SIGNED', 'ACTIVE', 'EXPIRED', 'TERMINATED_BY_OWNER',
        'TERMINATED_BY_TENANT', 'CANCELLED'
    )),
    CONSTRAINT ck_contract_payment_frequency CHECK (payment_frequency IN (
        'MONTHLY', 'BIWEEKLY', 'WEEKLY', 'QUARTERLY', 'YEARLY'
    )),
    CONSTRAINT ck_contract_dates CHECK (end_date > start_date),
    CONSTRAINT ck_contract_rent CHECK (monthly_rent > 0),
    CONSTRAINT ck_contract_deposit CHECK (security_deposit >= 0),
    CONSTRAINT ck_contract_payment_day CHECK (payment_day_of_month BETWEEN 1 AND 31),
    CONSTRAINT ck_contract_currency CHECK (length(trim(currency)) > 0 AND length(currency) <= 8),
    CONSTRAINT ck_contract_different_owner_and_tenant CHECK (owner_user_id <> tenant_user_id),
    CONSTRAINT ck_contract_terminated_from_status CHECK (terminated_from_status IN (
        'SIGNED', 'ACTIVE'
    ) OR terminated_from_status IS NULL),
    CONSTRAINT ck_contract_terminated_consistency CHECK (
        (status IN ('TERMINATED_BY_OWNER', 'TERMINATED_BY_TENANT')
            AND terminated_at IS NOT NULL AND terminated_by_user_id IS NOT NULL)
        OR (status NOT IN ('TERMINATED_BY_OWNER', 'TERMINATED_BY_TENANT'))
    ),
    CONSTRAINT ck_contract_cancelled_consistency CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
        OR (status <> 'CANCELLED')
    )
);

CREATE INDEX ix_contracts_owner ON lease_contracts (owner_user_id, updated_at DESC);
CREATE INDEX ix_contracts_tenant ON lease_contracts (tenant_user_id, updated_at DESC);
CREATE INDEX ix_contracts_property ON lease_contracts (property_id, status, created_at DESC);
CREATE INDEX ix_contracts_manager ON lease_contracts (real_estate_manager_user_id, updated_at DESC);
CREATE INDEX ix_contracts_status ON lease_contracts (status, start_date, end_date);
CREATE INDEX ix_contracts_original ON lease_contracts (original_contract_id);
