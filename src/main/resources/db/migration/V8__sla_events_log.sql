-- Tabla de tracking de eventos SLA enviados.
-- Evita spam: cada tipo de evento SLA (WARNING/BREACH) se envía una sola vez por request.

CREATE TABLE sla_events_log (
    id BIGSERIAL PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES repair_requests(id) ON DELETE CASCADE,
    event_type VARCHAR(16) NOT NULL CHECK (event_type IN ('SLA_WARNING', 'SLA_BREACH')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_sla_events_request_type UNIQUE (request_id, event_type)
);

CREATE INDEX ix_sla_events_request ON sla_events_log (request_id);
