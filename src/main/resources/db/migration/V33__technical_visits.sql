-- FR-UC-03: visitas técnicas agendadas entre quien tiene relación con el inmueble y un técnico.
-- FR-UC-27: la visita de valoración de cocina lleva un checklist por frente de obra.
-- Las partes se copian de la solicitud al crear la visita para que las reglas de acceso no
-- dependan de leer otro módulo.

CREATE TABLE technical_visits (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES repair_requests(id),
    property_id UUID REFERENCES properties(id),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    requested_by_user_id UUID REFERENCES users(id),
    manager_user_id UUID REFERENCES users(id),
    fixer_user_id UUID NOT NULL REFERENCES users(id),
    proposed_by_user_id UUID NOT NULL REFERENCES users(id),
    kind VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_minutes INTEGER NOT NULL,
    address_snapshot VARCHAR(255),
    notes VARCHAR(1000),
    cancel_reason VARCHAR(500),
    outcome VARCHAR(2000),
    reminder_sent_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_visit_kind CHECK (kind IN ('DIAGNOSTIC', 'EXECUTION', 'KITCHEN_ASSESSMENT')),
    CONSTRAINT ck_visit_status CHECK (status IN ('PROPOSED', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    CONSTRAINT ck_visit_duration CHECK (duration_minutes BETWEEN 30 AND 240),
    CONSTRAINT ck_visit_parties CHECK (fixer_user_id <> owner_user_id),
    CONSTRAINT ck_visit_completion CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL) OR (status <> 'COMPLETED' AND completed_at IS NULL)
    ),
    CONSTRAINT ck_visit_cancel_reason CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL)
);

CREATE INDEX ix_visits_request ON technical_visits (request_id, scheduled_at);
CREATE INDEX ix_visits_fixer ON technical_visits (fixer_user_id, scheduled_at);
CREATE INDEX ix_visits_owner ON technical_visits (owner_user_id, scheduled_at);
CREATE INDEX ix_visits_reminder ON technical_visits (status, reminder_sent_at, scheduled_at);

CREATE TABLE visit_checklist_items (
    visit_id UUID NOT NULL REFERENCES technical_visits(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    label VARCHAR(120) NOT NULL,
    result VARCHAR(16),
    note VARCHAR(500),
    PRIMARY KEY (visit_id, position),
    CONSTRAINT ck_visit_checklist_result CHECK (result IS NULL OR result IN ('OK', 'NEEDS_WORK', 'NOT_APPLICABLE'))
);
