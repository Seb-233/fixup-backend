ALTER TABLE repair_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE repair_payments FORCE ROW LEVEL SECURITY;
CREATE POLICY repair_payments_select ON repair_payments FOR SELECT USING (
    app_current_user_has_role('PLATFORM_ADMIN')
    OR owner_user_id = app_current_user_id()
    OR fixer_user_id = app_current_user_id()
);
CREATE POLICY repair_payments_insert ON repair_payments FOR INSERT WITH CHECK (
    app_current_user_has_role('PLATFORM_ADMIN')
    OR owner_user_id = app_current_user_id()
);
CREATE POLICY repair_payments_update ON repair_payments FOR UPDATE USING (
    app_current_user_has_role('PLATFORM_ADMIN') OR owner_user_id = app_current_user_id()
) WITH CHECK (
    app_current_user_has_role('PLATFORM_ADMIN') OR owner_user_id = app_current_user_id()
);

ALTER TABLE owner_debts ENABLE ROW LEVEL SECURITY;
ALTER TABLE owner_debts FORCE ROW LEVEL SECURITY;
CREATE POLICY owner_debts_select ON owner_debts FOR SELECT USING (
    app_current_user_has_role('PLATFORM_ADMIN')
    OR owner_user_id = app_current_user_id()
    OR fixer_user_id = app_current_user_id()
);
CREATE POLICY owner_debts_insert ON owner_debts FOR INSERT WITH CHECK (
    app_current_user_has_role('PLATFORM_ADMIN') OR owner_user_id = app_current_user_id()
);
CREATE POLICY owner_debts_update ON owner_debts FOR UPDATE USING (
    app_current_user_has_role('PLATFORM_ADMIN') OR owner_user_id = app_current_user_id()
) WITH CHECK (
    app_current_user_has_role('PLATFORM_ADMIN') OR owner_user_id = app_current_user_id()
);

ALTER TABLE simulated_payment_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE simulated_payment_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY simulated_payment_attempts_select ON simulated_payment_attempts FOR SELECT USING (
    app_current_user_has_role('PLATFORM_ADMIN') OR EXISTS (
        SELECT 1 FROM repair_payments payment
        WHERE payment.id = simulated_payment_attempts.payment_id
          AND (payment.owner_user_id = app_current_user_id() OR payment.fixer_user_id = app_current_user_id())
    )
);
CREATE POLICY simulated_payment_attempts_insert ON simulated_payment_attempts FOR INSERT WITH CHECK (
    app_current_user_has_role('PLATFORM_ADMIN') OR EXISTS (
        SELECT 1 FROM repair_payments payment
        WHERE payment.id = simulated_payment_attempts.payment_id
          AND payment.owner_user_id = app_current_user_id()
    )
);
