-- FR-UC-08: urgencia, reloj de SLA y estados avanzados de una solicitud de reparación.
--
-- Renumerada desde la V6 de la rama de contratos: aquella asumía un esquema en el que solo habían
-- corrido V1..V5, y aquí llega encima de V13__fixer_consent y V15__repair_request_property. Los
-- ALTER siguen siendo válidos porque ninguna de esas dos tocó status ni la restricción de asignación.

-- 1. Los estados que la solicitud puede tomar ahora que el trabajo tiene ciclo de vida propio.
ALTER TABLE repair_requests DROP CONSTRAINT ck_request_status;
ALTER TABLE repair_requests ADD CONSTRAINT ck_request_status CHECK (
    status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'ON_HOLD')
);

-- 2. La asignación deja de ser binaria: todo estado posterior a ASSIGNED conserva al técnico, y
--    CANCELLED puede tener o no tenerlo según desde dónde se canceló.
ALTER TABLE repair_requests DROP CONSTRAINT ck_request_assignment;
ALTER TABLE repair_requests ADD CONSTRAINT ck_request_assignment CHECK (
    (status = 'OPEN' AND assigned_fixer_user_id IS NULL)
    OR (status IN ('ASSIGNED', 'IN_PROGRESS', 'ON_HOLD', 'COMPLETED') AND assigned_fixer_user_id IS NOT NULL)
    OR (status = 'CANCELLED')
);

-- 3. Urgencia. Las filas que ya existen quedan en MEDIUM, que es el valor por omisión del dominio.
ALTER TABLE repair_requests ADD COLUMN urgency VARCHAR(16) DEFAULT 'MEDIUM' NOT NULL;
ALTER TABLE repair_requests ADD CONSTRAINT ck_request_urgency CHECK (
    urgency IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')
);

-- 4. Reloj de SLA. Deliberadamente NO se rellena para las filas anteriores: un plazo que nadie
--    pudo conocer no es un compromiso de servicio. Calcularlo desde created_at, como hacía la
--    migración original, dejaría incumplida de nacimiento a toda solicitud de más de tres días y
--    el primer barrido dispararía una notificación de incumplimiento por cada una. El barrido ya
--    filtra por sla_deadline IS NOT NULL, así que estas filas simplemente quedan fuera del SLA.
ALTER TABLE repair_requests ADD COLUMN sla_deadline TIMESTAMP WITH TIME ZONE;

CREATE INDEX ix_repair_requests_sla ON repair_requests (status, sla_deadline);
