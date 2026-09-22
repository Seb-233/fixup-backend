-- FR-UC-10: bandeja de notificaciones. Cada notificación pertenece a un único usuario y guarda el
-- tipo, el título, el mensaje y, opcionalmente, la entidad a la que se refiere.
--
-- Renumerada desde la V7 de la rama de contratos. Dos cambios respecto del original: el índice de
-- no leídas deja de ser parcial, porque H2 no admite CREATE INDEX ... WHERE y las pruebas de
-- contrato corren contra H2 antes que contra PostgreSQL; y el CHECK de estado de lectura se
-- conserva, que es lo que impide una fila marcada como leída sin fecha de lectura.

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    related_entity_id UUID,
    is_read BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    read_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_notification_title CHECK (length(trim(title)) > 0),
    CONSTRAINT ck_notification_message CHECK (length(trim(message)) > 0),
    CONSTRAINT ck_notification_read_at CHECK (
        (is_read = FALSE AND read_at IS NULL) OR (is_read = TRUE AND read_at IS NOT NULL)
    )
);

CREATE INDEX ix_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_user_unread ON notifications (user_id, is_read);
