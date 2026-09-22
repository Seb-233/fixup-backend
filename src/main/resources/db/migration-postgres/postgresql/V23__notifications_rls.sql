-- FR-UC-10 + FR-UC-25: aislamiento por usuario de la bandeja de notificaciones, en la base y no
-- solo en ListMyNotifications. Mismo modelo que el resto de tablas con RLS: se reutilizan
-- app_current_user_id() y app_current_user_has_role() de V10__row_level_security.sql.
--
-- Una notificación solo la ve y solo la marca como leída su destinatario. PLATFORM_ADMIN tiene
-- lectura, igual que en properties. La inserción la hace el backend reaccionando a eventos de
-- otros usuarios —a un propietario se le notifica lo que hizo un técnico—, así que no puede
-- exigirse que el insertante sea el destinatario; el índice de no leídas parcial vive aquí, donde
-- PostgreSQL sí lo admite.

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications FORCE ROW LEVEL SECURITY;

CREATE POLICY notifications_select ON notifications FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR user_id = app_current_user_id()
    );

CREATE POLICY notifications_insert ON notifications FOR INSERT
    WITH CHECK (TRUE);

CREATE POLICY notifications_update ON notifications FOR UPDATE
    USING (user_id = app_current_user_id())
    WITH CHECK (user_id = app_current_user_id());

DROP INDEX ix_notifications_user_unread;
CREATE INDEX ix_notifications_user_unread ON notifications (user_id) WHERE is_read = FALSE;
