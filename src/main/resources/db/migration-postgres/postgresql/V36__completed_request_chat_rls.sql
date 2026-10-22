-- FR-UC-24 y FR-UC-05: una solicitud CASH completada puede quedar con saldo pendiente, así que su
-- conversación sigue abierta después de cerrar el trabajo; cualquier otra solicitud completada queda
-- solo para lectura, como en V30. Reemplaza la regla de escritura de V30 conservando a sus
-- participantes (dueño, quien reportó, administrador y técnico asignado).
DROP POLICY chat_messages_insert ON chat_messages;
CREATE POLICY chat_messages_insert ON chat_messages FOR INSERT
    WITH CHECK (
        sender_user_id = app_current_user_id()
        AND EXISTS (
            SELECT 1 FROM repair_requests r
            WHERE r.id = chat_messages.request_id
              AND r.assigned_fixer_user_id IS NOT NULL
              AND r.status NOT IN ('OPEN', 'CANCELLED')
              AND (r.status <> 'COMPLETED' OR r.payment_method = 'CASH')
              AND (r.owner_user_id = app_current_user_id()
                   OR r.requested_by_user_id = app_current_user_id()
                   OR r.manager_user_id = app_current_user_id()
                   OR r.assigned_fixer_user_id = app_current_user_id())
        )
    );
