-- FR-UC-05 sobre la entrega 1: V38 vuelve a definir la escritura del chat y los pagos ahora tienen
-- administrador inmobiliario (V39). Estas reglas reemplazan las de V26, V28 y V29 para incluirlo.

-- Lo ven quien lo debe (el propietario), el administrador inmobiliario del inmueble delegado, el
-- técnico que lo recibe y el admin. Nace en la transacción de quien acepta la cotización (dueño o
-- administrador) y lo actualiza quien paga.
DROP POLICY repair_payments_select ON repair_payments;
CREATE POLICY repair_payments_select ON repair_payments FOR SELECT USING (
    app_current_user_has_role('PLATFORM_ADMIN')
    OR owner_user_id = app_current_user_id()
    OR manager_user_id = app_current_user_id()
    OR fixer_user_id = app_current_user_id()
);
DROP POLICY repair_payments_insert ON repair_payments;
CREATE POLICY repair_payments_insert ON repair_payments FOR INSERT WITH CHECK (
    app_current_user_has_role('PLATFORM_ADMIN')
    OR owner_user_id = app_current_user_id()
    OR manager_user_id = app_current_user_id()
);
DROP POLICY repair_payments_update ON repair_payments;
CREATE POLICY repair_payments_update ON repair_payments FOR UPDATE USING (
    app_current_user_has_role('PLATFORM_ADMIN') OR owner_user_id = app_current_user_id()
    OR manager_user_id = app_current_user_id()
) WITH CHECK (
    app_current_user_has_role('PLATFORM_ADMIN') OR owner_user_id = app_current_user_id()
    OR manager_user_id = app_current_user_id()
);

DROP POLICY simulated_payment_attempts_select ON simulated_payment_attempts;
CREATE POLICY simulated_payment_attempts_select ON simulated_payment_attempts FOR SELECT USING (
    app_current_user_has_role('PLATFORM_ADMIN') OR EXISTS (
        SELECT 1 FROM repair_payments payment
        WHERE payment.id = simulated_payment_attempts.payment_id
          AND (payment.owner_user_id = app_current_user_id() OR payment.manager_user_id = app_current_user_id()
               OR payment.fixer_user_id = app_current_user_id())
    )
);
DROP POLICY simulated_payment_attempts_insert ON simulated_payment_attempts;
CREATE POLICY simulated_payment_attempts_insert ON simulated_payment_attempts FOR INSERT WITH CHECK (
    app_current_user_has_role('PLATFORM_ADMIN') OR EXISTS (
        SELECT 1 FROM repair_payments payment
        WHERE payment.id = simulated_payment_attempts.payment_id
          AND (payment.owner_user_id = app_current_user_id() OR payment.manager_user_id = app_current_user_id())
    )
);

-- El administrador inmobiliario ve el efectivo de las solicitudes de los inmuebles que administra.
DROP POLICY cash_settlements_select ON cash_settlements;
CREATE POLICY cash_settlements_select ON cash_settlements FOR SELECT USING (
    app_current_user_has_role('PLATFORM_ADMIN')
    OR owner_user_id = app_current_user_id()
    OR fixer_user_id = app_current_user_id()
    OR EXISTS (
        SELECT 1 FROM repair_requests r
        WHERE r.id = cash_settlements.request_id AND r.manager_user_id = app_current_user_id()
    )
);

-- FR-UC-24 y FR-UC-05: una solicitud CASH completada puede quedar con saldo pendiente, así que su
-- conversación sigue abierta después de cerrar el trabajo; cualquier otra solicitud completada queda
-- solo para lectura, como en V38. Reemplaza la regla de escritura de V38 conservando a sus
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
