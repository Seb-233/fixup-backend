-- FR-UC-08 (SLA), FR-UC-10 (Notificaciones), FR-UC-12 (Bulk Properties status ya en V16), FR-UC-14 (Contratos)
-- Postgres + H2 compatible (VARCHAR sin long específico en H2 acepta, NUMERIC/DECIMAL ok, CLOB para JSON fallback en H2)

-- ============== FR-UC-08 Urgency + SLA ==============

-- RepairRequestStatus ampliar (OPEN,ASSIGNED ya existentes)
ALTER TABLE repair_requests DROP CONSTRAINT IF EXISTS ck_request_status;
ALTER TABLE repair_requests ADD CONSTRAINT ck_request_status CHECK (
    status IN ('OPEN', 'ASSIGNED', 'SLA_WARNING', 'SLA_BREACHED', 'COMPLETED', 'CANCELLED')
);

-- Assignment check relajado: solo ASSIGNED requiere fixer
ALTER TABLE repair_requests DROP CONSTRAINT IF EXISTS ck_request_assignment;
ALTER TABLE repair_requests ADD CONSTRAINT ck_request_assignment CHECK (
    (status IN ('OPEN', 'COMPLETED', 'CANCELLED', 'SLA_WARNING', 'SLA_BREACHED'))
    OR (status = 'ASSIGNED' AND assigned_fixer_user_id IS NOT NULL)
);

ALTER TABLE repair_requests ADD COLUMN IF NOT EXISTS urgency_level VARCHAR(16) NOT NULL DEFAULT 'MEDIUM';
ALTER TABLE repair_requests DROP CONSTRAINT IF EXISTS ck_request_urgency;
ALTER TABLE repair_requests ADD CONSTRAINT ck_request_urgency CHECK (
    urgency_level IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')
);

ALTER TABLE repair_requests ADD COLUMN IF NOT EXISTS sla_deadline TIMESTAMP WITH TIME ZONE;
ALTER TABLE repair_requests ADD COLUMN IF NOT EXISTS property_city VARCHAR(100);
ALTER TABLE repair_requests ADD COLUMN IF NOT EXISTS last_escalation_notified_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS ix_repair_requests_sla ON repair_requests (urgency_level, sla_deadline, status);

-- Denormalizar property.city inicial a repair_requests.property_city (para filtro cercanía por ciudad)
UPDATE repair_requests
SET property_city = (SELECT p.city FROM properties p WHERE p.id = repair_requests.property_id)
WHERE property_city IS NULL AND EXISTS (SELECT 1 FROM properties p WHERE p.id = repair_requests.property_id);

-- Properties ampliar lat/lon
ALTER TABLE properties ADD COLUMN IF NOT EXISTS latitude NUMERIC(10,6);
ALTER TABLE properties ADD COLUMN IF NOT EXISTS longitude NUMERIC(10,6);

-- FixerProfiles ampliar city/lat/lon
ALTER TABLE fixer_profiles ADD COLUMN IF NOT EXISTS city VARCHAR(100);
ALTER TABLE fixer_profiles ADD COLUMN IF NOT EXISTS latitude NUMERIC(10,6);
ALTER TABLE fixer_profiles ADD COLUMN IF NOT EXISTS longitude NUMERIC(10,6);

-- ============== FR-UC-10 Notificaciones ==============

CREATE TABLE IF NOT EXISTS user_devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_token VARCHAR(500) NOT NULL,
    platform VARCHAR(32),
    last_seen_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_device_platform CHECK (platform IS NULL OR platform IN ('ANDROID', 'IOS', 'WEB'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_device_token ON user_devices (user_id, device_token);
CREATE INDEX IF NOT EXISTS ix_user_devices_user ON user_devices (user_id);

CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY,
    recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    navigate_to VARCHAR(500),
    entity_id UUID,
    entity_type VARCHAR(64),
    data JSON,
    read_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_notification_type CHECK (type IN (
        'QUOTATION_ACCEPTED',
        'QUOTATION_SUBMITTED',
        'JOB_CREATED',
        'JOB_COMPLETED',
        'SLA_WARNING',
        'SLA_BREACH',
        'PROPERTY_PUBLISHED',
        'CONTRACT_CREATED',
        'CONTRACT_EXPIRING_SOON',
        'CONTRACT_EXPIRED',
        'CONTRACT_RENEWED',
        'GENERIC'
    ))
);
CREATE INDEX IF NOT EXISTS ix_notifications_user_created ON notifications (recipient_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_notifications_user_unread ON notifications (recipient_user_id, read_at, created_at DESC);

-- ============== FR-UC-14 Contratos Arrendamiento ==============

CREATE TABLE IF NOT EXISTS rental_contracts (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL REFERENCES properties(id),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    tenant_user_id UUID NOT NULL REFERENCES users(id),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    monthly_rent_amount BIGINT NOT NULL,
    deposit_amount BIGINT NOT NULL,
    payment_day_of_month INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    renewal_notice_days INTEGER NOT NULL DEFAULT 30,
    notes VARCHAR(2000),
    reminder_30d_sent BOOLEAN NOT NULL DEFAULT FALSE,
    reminder_7d_sent BOOLEAN NOT NULL DEFAULT FALSE,
    reminder_expired_sent BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_contract_status CHECK (
        status IN ('DRAFT', 'ACTIVE', 'RENEWED', 'EXPIRED', 'TERMINATED')
    ),
    CONSTRAINT ck_contract_rent CHECK (monthly_rent_amount > 0 AND monthly_rent_amount <= 9007199254740991),
    CONSTRAINT ck_contract_deposit CHECK (deposit_amount >= 0 AND deposit_amount <= 9007199254740991),
    CONSTRAINT ck_contract_payment_day CHECK (payment_day_of_month BETWEEN 1 AND 28),
    CONSTRAINT ck_contract_renewal_days CHECK (renewal_notice_days BETWEEN 0 AND 365),
    CONSTRAINT ck_contract_dates CHECK (end_date > start_date)
);

CREATE INDEX IF NOT EXISTS ix_rental_contracts_property ON rental_contracts (property_id, start_date, end_date);
CREATE INDEX IF NOT EXISTS ix_rental_contracts_owner ON rental_contracts (owner_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_rental_contracts_tenant ON rental_contracts (tenant_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_rental_contracts_expiring ON rental_contracts (end_date, status);
