-- FR-UC-10: Alignar restricci¾n ck_notification_type con enum Java NotificationType
-- Los valores de V17 eran antiguos. Actualizar para permitir los eventos reales del sistema.
ALTER TABLE notifications DROP CONSTRAINT IF EXISTS ck_notification_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notification_type CHECK (type IN (
    'REQUEST_CREATED_URGENT',
    'REQUEST_SLA_WARNING',
    'REQUEST_SLA_BREACHED',
    'QUOTATION_RECEIVED',
    'QUOTATION_ACCEPTED',
    'JOB_ASSIGNED',
    'PROPERTY_PUBLISHED',
    'PROPERTY_BULK_FINISHED',
    'CONTRACT_CREATED',
    'CONTRACT_EXPIRING_30D',
    'CONTRACT_EXPIRING_7D',
    'CONTRACT_EXPIRED',
    'CHAT_MESSAGE_RECEIVED'
));
