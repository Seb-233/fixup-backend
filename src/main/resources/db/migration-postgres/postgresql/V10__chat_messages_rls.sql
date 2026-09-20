-- FR-UC-24: mismo modelo de confianza que FR-UC-25/FR-UC-23 -- reusa app_current_user_id()/
-- app_current_user_has_role() de V6 en vez de reinventar la lectura de sesion. Solo el dueño de la
-- solicitud y el fixer asignado son participantes autorizados del chat; PLATFORM_ADMIN puede leer
-- para moderacion/soporte pero nunca se hace pasar por un participante (sin politica de INSERT para
-- admin a proposito). No hay politica de UPDATE/DELETE: un mensaje enviado es inmutable, la
-- trazabilidad no se negocia.

ALTER TABLE chat_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_messages FORCE ROW LEVEL SECURITY;

CREATE POLICY chat_messages_select ON chat_messages FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR EXISTS (
            SELECT 1 FROM repair_requests r
            WHERE r.id = chat_messages.request_id
              AND (r.owner_user_id = app_current_user_id() OR r.assigned_fixer_user_id = app_current_user_id())
        )
    );

-- KNOWN LIMITATION / EXTENSION POINT: this WITH CHECK matches status = 'ASSIGNED' exactly, not
-- "any status where a fixer is confirmed". Today ASSIGNED is the only non-OPEN status, so this is
-- complete. If a future terminal status is added (e.g. COMPLETED), a request moving into it stops
-- accepting new messages immediately -- both here and in SendChatMessage's isAssigned() check --
-- while chat_messages_select above keeps the full history readable forever (it never filters on
-- status). That is a silent behavior change, not a decision: whoever adds the new status must
-- explicitly choose whether the chat should close on completion (update nothing) or stay writable
-- for wrap-up messages (loosen this clause, e.g. to status IN ('ASSIGNED', 'COMPLETED')).
CREATE POLICY chat_messages_insert ON chat_messages FOR INSERT
    WITH CHECK (
        sender_user_id = app_current_user_id()
        AND EXISTS (
            SELECT 1 FROM repair_requests r
            WHERE r.id = chat_messages.request_id
              AND r.status = 'ASSIGNED'
              AND (r.owner_user_id = app_current_user_id() OR r.assigned_fixer_user_id = app_current_user_id())
        )
    );
