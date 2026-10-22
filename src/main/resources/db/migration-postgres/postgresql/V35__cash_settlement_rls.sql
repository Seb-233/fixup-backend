ALTER TABLE cash_settlements ENABLE ROW LEVEL SECURITY;
ALTER TABLE cash_settlements FORCE ROW LEVEL SECURITY;
-- El administrador inmobiliario ve el efectivo de las solicitudes de los inmuebles que administra.
CREATE POLICY cash_settlements_select ON cash_settlements FOR SELECT USING (
    app_current_user_has_role('PLATFORM_ADMIN')
    OR owner_user_id = app_current_user_id()
    OR fixer_user_id = app_current_user_id()
    OR EXISTS (
        SELECT 1 FROM repair_requests r
        WHERE r.id = cash_settlements.request_id AND r.manager_user_id = app_current_user_id()
    )
);
CREATE POLICY cash_settlements_insert ON cash_settlements FOR INSERT WITH CHECK (
    app_current_user_has_role('PLATFORM_ADMIN')
    OR (fixer_user_id = app_current_user_id() AND EXISTS (
        SELECT 1 FROM jobs j WHERE j.id = cash_settlements.job_id
          AND j.fixer_user_id = app_current_user_id()
          AND j.quotation_id = cash_settlements.quotation_id
          AND j.request_id = cash_settlements.request_id
          AND j.owner_user_id = cash_settlements.owner_user_id
          AND j.agreed_amount = cash_settlements.agreed_amount
          AND j.payment_method = 'CASH' AND j.status = 'COMPLETED'
    ))
);

-- A fixer closing a CASH job must be able to record the missing balance. The
-- application owns the insert; the policy confines it to that completed job.
DROP POLICY owner_debts_insert ON owner_debts;
CREATE POLICY owner_debts_insert ON owner_debts FOR INSERT WITH CHECK (
    app_current_user_has_role('PLATFORM_ADMIN') OR owner_user_id = app_current_user_id()
    OR (fixer_user_id = app_current_user_id() AND EXISTS (
        SELECT 1 FROM cash_settlements s
        WHERE s.quotation_id = owner_debts.quotation_id
          AND s.owner_user_id = owner_debts.owner_user_id
          AND s.fixer_user_id = app_current_user_id()
          AND s.outstanding_amount = owner_debts.amount
    ))
);
