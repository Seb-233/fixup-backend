-- FR-UC-08: Soporte de urgencia, SLA y estados avanzados para reparaciones
-- Ampliar los estados admitidos, agregar columnas urgency y sla_deadline.

-- 1. Eliminar el CHECK viejo de status y recrearlo con los nuevos estados
ALTER TABLE repair_requests DROP CONSTRAINT ck_request_status;
ALTER TABLE repair_requests ADD CONSTRAINT ck_request_status CHECK (
    status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'ON_HOLD')
);

-- 2. Eliminar la constraint de asignación estricta.
--    Estados sin fixer asignado: OPEN, CANCELLED (solo si estaba OPEN)
--    Estados con fixer asignado: ASSIGNED, IN_PROGRESS, ON_HOLD, COMPLETED
--    CANCELLED puede tener o no fixer (dependiendo de en qué estado estaba al cancelar)
ALTER TABLE repair_requests DROP CONSTRAINT ck_request_assignment;
ALTER TABLE repair_requests ADD CONSTRAINT ck_request_assignment CHECK (
    (status IN ('OPEN') AND assigned_fixer_user_id IS NULL)
    OR (status IN ('ASSIGNED', 'IN_PROGRESS', 'ON_HOLD', 'COMPLETED') AND assigned_fixer_user_id IS NOT NULL)
    OR (status = 'CANCELLED')
);

-- 3. Agregar columna urgency (no admite nulos, por defecto MEDIUM)
ALTER TABLE repair_requests ADD COLUMN urgency VARCHAR(16);
UPDATE repair_requests SET urgency = 'MEDIUM' WHERE urgency IS NULL;
ALTER TABLE repair_requests ALTER COLUMN urgency SET NOT NULL;
ALTER TABLE repair_requests ADD CONSTRAINT ck_request_urgency CHECK (
    urgency IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')
);

-- 4. Recalcular sla_deadline para registros existentes en función de urgency + created_at
ALTER TABLE repair_requests ADD COLUMN sla_deadline TIMESTAMP WITH TIME ZONE;
UPDATE repair_requests SET sla_deadline =
    CASE urgency
        WHEN 'LOW' THEN created_at + INTERVAL '7 days'
        WHEN 'MEDIUM' THEN created_at + INTERVAL '3 days'
        WHEN 'HIGH' THEN created_at + INTERVAL '24 hours'
        WHEN 'URGENT' THEN created_at + INTERVAL '4 hours'
        ELSE created_at + INTERVAL '3 days'
    END
WHERE sla_deadline IS NULL;
