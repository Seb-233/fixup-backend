-- Invoker functions: no elevated role, no session impersonation and no use of V38's debt capability.
CREATE FUNCTION financial_actor_role(required_role text) RETURNS boolean
LANGUAGE sql STABLE SET search_path = ${flyway:defaultSchema}, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id = u.id
        WHERE u.id = app_current_user_id() AND u.status = 'ACTIVE' AND ur.role = required_role)
$$;
CREATE FUNCTION financial_current_manager(property_id uuid, responsible_owner uuid) RETURNS boolean
LANGUAGE sql STABLE SET search_path = ${flyway:defaultSchema}, pg_temp AS $$
    SELECT financial_actor_role('REAL_ESTATE_MANAGER') AND EXISTS (
        SELECT 1 FROM properties p WHERE p.id = property_id AND p.owner_user_id = responsible_owner
            AND p.manager_user_id = app_current_user_id())
$$;
CREATE FUNCTION financial_payer(property_id uuid, responsible_owner uuid) RETURNS boolean
LANGUAGE sql STABLE SET search_path = ${flyway:defaultSchema}, pg_temp AS $$
    SELECT (financial_actor_role('OWNER') AND responsible_owner = app_current_user_id())
        OR financial_current_manager(property_id, responsible_owner)
$$;

ALTER TABLE repair_payment_contexts ENABLE ROW LEVEL SECURITY;
ALTER TABLE repair_payment_contexts FORCE ROW LEVEL SECURITY;
CREATE POLICY payment_context_select ON repair_payment_contexts FOR SELECT USING (
    financial_payer(property_id, owner_user_id) OR financial_actor_role('PLATFORM_ADMIN')
    OR (financial_actor_role('FIXER') AND fixer_user_id = app_current_user_id())
);
CREATE POLICY payment_context_insert ON repair_payment_contexts FOR INSERT WITH CHECK (
    source = 'ACCEPTANCE' AND financial_payer(property_id, owner_user_id)
    AND EXISTS (SELECT 1 FROM properties p WHERE p.id = repair_payment_contexts.property_id
        AND p.owner_user_id = repair_payment_contexts.owner_user_id
        AND p.manager_user_id IS NOT DISTINCT FROM repair_payment_contexts.manager_user_id)
    AND EXISTS (SELECT 1 FROM quotations q JOIN repair_requests r ON r.id = q.request_id
        WHERE q.id = quotation_id AND q.status = 'ACCEPTED' AND r.id = repair_payment_contexts.request_id
            AND r.property_id = repair_payment_contexts.property_id
            AND r.owner_user_id = repair_payment_contexts.owner_user_id
            AND q.fixer_user_id = repair_payment_contexts.fixer_user_id
            AND r.specialty = repair_payment_contexts.specialty AND r.title = repair_payment_contexts.concept)
);

-- Current delegation, not the request's historical manager snapshot, permits financial decisions.
CREATE POLICY repair_requests_financial_manager_select ON repair_requests FOR SELECT
    USING (financial_current_manager(property_id, owner_user_id));
CREATE POLICY repair_requests_financial_manager_update ON repair_requests FOR UPDATE
    USING (financial_current_manager(property_id, owner_user_id))
    WITH CHECK (financial_current_manager(property_id, owner_user_id));
CREATE POLICY quotations_financial_manager_select ON quotations FOR SELECT USING (
    EXISTS (SELECT 1 FROM repair_requests r WHERE r.id = request_id AND financial_current_manager(r.property_id, r.owner_user_id))
);
CREATE POLICY quotations_financial_manager_update ON quotations FOR UPDATE USING (
    EXISTS (SELECT 1 FROM repair_requests r WHERE r.id = request_id AND financial_current_manager(r.property_id, r.owner_user_id))
) WITH CHECK (
    EXISTS (SELECT 1 FROM repair_requests r WHERE r.id = request_id AND financial_current_manager(r.property_id, r.owner_user_id))
);

DROP POLICY repair_payments_select ON repair_payments;
CREATE POLICY repair_payments_select ON repair_payments FOR SELECT USING (
    financial_actor_role('PLATFORM_ADMIN')
    OR (financial_actor_role('OWNER') AND owner_user_id = app_current_user_id())
    OR (financial_actor_role('FIXER') AND fixer_user_id = app_current_user_id())
    OR EXISTS (SELECT 1 FROM repair_payment_contexts c WHERE c.quotation_id = repair_payments.quotation_id
        AND c.owner_user_id = repair_payments.owner_user_id AND financial_current_manager(c.property_id, c.owner_user_id))
);
DROP POLICY repair_payments_insert ON repair_payments;
CREATE POLICY repair_payments_insert ON repair_payments FOR INSERT WITH CHECK (
    status = 'PENDING' AND payment_method = 'TRANSFER'
    AND EXISTS (SELECT 1 FROM repair_payment_contexts c JOIN quotations q ON q.id = c.quotation_id
        WHERE c.quotation_id = repair_payments.quotation_id AND c.request_id = repair_payments.request_id
            AND c.owner_user_id = repair_payments.owner_user_id AND c.fixer_user_id = repair_payments.fixer_user_id
            AND financial_payer(c.property_id, c.owner_user_id) AND q.status = 'ACCEPTED'
            AND ((purpose = 'SERVICE' AND repair_payments.amount = q.amount)
                OR (purpose = 'OUTSTANDING_BALANCE' AND EXISTS (SELECT 1 FROM owner_debts d
                    WHERE d.quotation_id = c.quotation_id AND d.request_id = c.request_id
                        AND d.owner_user_id = c.owner_user_id AND d.fixer_user_id = c.fixer_user_id
                        AND d.status = 'OUTSTANDING' AND d.amount = repair_payments.amount))))
);
-- Historical debts without reconstructable context remain payable only by their canonical owner.
CREATE POLICY repair_payments_legacy_debt_insert ON repair_payments FOR INSERT WITH CHECK (
    purpose = 'OUTSTANDING_BALANCE' AND status = 'PENDING' AND payment_method = 'TRANSFER'
    AND financial_actor_role('OWNER') AND owner_user_id = app_current_user_id()
    AND EXISTS (SELECT 1 FROM owner_debts d WHERE d.quotation_id = repair_payments.quotation_id
        AND d.request_id = repair_payments.request_id AND d.owner_user_id = repair_payments.owner_user_id
        AND d.fixer_user_id = repair_payments.fixer_user_id AND d.amount = repair_payments.amount AND d.status = 'OUTSTANDING')
);
DROP POLICY repair_payments_update ON repair_payments;
CREATE POLICY repair_payments_update ON repair_payments FOR UPDATE USING (
    (financial_actor_role('OWNER') AND owner_user_id = app_current_user_id())
    OR EXISTS (SELECT 1 FROM repair_payment_contexts c WHERE c.quotation_id = repair_payments.quotation_id
        AND c.owner_user_id = repair_payments.owner_user_id AND financial_current_manager(c.property_id, c.owner_user_id))
) WITH CHECK (
    (financial_actor_role('OWNER') AND owner_user_id = app_current_user_id())
    OR EXISTS (SELECT 1 FROM repair_payment_contexts c WHERE c.quotation_id = repair_payments.quotation_id
        AND c.owner_user_id = repair_payments.owner_user_id AND financial_current_manager(c.property_id, c.owner_user_id))
);

DROP POLICY simulated_payment_attempts_select ON simulated_payment_attempts;
CREATE POLICY simulated_payment_attempts_select ON simulated_payment_attempts FOR SELECT USING (
    EXISTS (SELECT 1 FROM repair_payments p WHERE p.id = payment_id)
);
DROP POLICY simulated_payment_attempts_insert ON simulated_payment_attempts;
CREATE POLICY simulated_payment_attempts_insert ON simulated_payment_attempts FOR INSERT WITH CHECK (
    attempted_by_user_id = app_current_user_id() AND operation_key IS NOT NULL
    AND EXISTS (SELECT 1 FROM repair_payments p LEFT JOIN repair_payment_contexts c ON c.quotation_id = p.quotation_id
        WHERE p.id = payment_id AND p.reference = payment_reference AND p.amount = simulated_payment_attempts.amount
            AND p.status <> 'APPROVED' AND financial_payer(c.property_id, p.owner_user_id))
);

DROP POLICY owner_debts_select ON owner_debts;
CREATE POLICY owner_debts_select ON owner_debts FOR SELECT TO fixup_app USING (
    financial_actor_role('PLATFORM_ADMIN')
    OR (financial_actor_role('OWNER') AND owner_user_id = app_current_user_id())
    OR (financial_actor_role('FIXER') AND fixer_user_id = app_current_user_id())
);
DROP POLICY owner_debts_update ON owner_debts;
CREATE POLICY owner_debts_update ON owner_debts FOR UPDATE TO fixup_app USING (
    financial_actor_role('PLATFORM_ADMIN') OR (financial_actor_role('OWNER') AND owner_user_id = app_current_user_id())
) WITH CHECK (
    financial_actor_role('PLATFORM_ADMIN') OR (financial_actor_role('OWNER') AND owner_user_id = app_current_user_id())
);
-- Only the application role needs the new metadata path. V38's private reader keeps its
-- existing narrowly scoped policy and gains no access to repair_payment_contexts.
CREATE POLICY owner_debts_financial_manager_select ON owner_debts FOR SELECT TO fixup_app USING (
    EXISTS (SELECT 1 FROM repair_payment_contexts c WHERE c.quotation_id = owner_debts.quotation_id
        AND c.owner_user_id = owner_debts.owner_user_id AND financial_current_manager(c.property_id, c.owner_user_id))
);
CREATE POLICY owner_debts_financial_manager_update ON owner_debts FOR UPDATE TO fixup_app USING (
    EXISTS (SELECT 1 FROM repair_payment_contexts c WHERE c.quotation_id = owner_debts.quotation_id
        AND c.owner_user_id = owner_debts.owner_user_id AND financial_current_manager(c.property_id, c.owner_user_id))
) WITH CHECK (
    EXISTS (SELECT 1 FROM repair_payment_contexts c WHERE c.quotation_id = owner_debts.quotation_id
        AND c.owner_user_id = owner_debts.owner_user_id AND financial_current_manager(c.property_id, c.owner_user_id))
);
DROP POLICY cash_settlements_select ON cash_settlements;
CREATE POLICY cash_settlements_select ON cash_settlements FOR SELECT USING (
    financial_actor_role('PLATFORM_ADMIN')
    OR (financial_actor_role('OWNER') AND owner_user_id = app_current_user_id())
    OR (financial_actor_role('FIXER') AND fixer_user_id = app_current_user_id())
);
CREATE POLICY cash_settlements_financial_manager_select ON cash_settlements FOR SELECT USING (
    EXISTS (SELECT 1 FROM repair_payment_contexts c WHERE c.quotation_id = cash_settlements.quotation_id
        AND c.owner_user_id = cash_settlements.owner_user_id AND financial_current_manager(c.property_id, c.owner_user_id))
);

-- Preserve the canonical initial-log convention; the actual payer is recorded in immutable attempts.
DROP POLICY job_status_log_insert ON job_status_log;
CREATE POLICY job_status_log_insert ON job_status_log FOR INSERT WITH CHECK (
    EXISTS (SELECT 1 FROM jobs j JOIN repair_requests r ON r.id = j.request_id
        JOIN users actor ON actor.id = app_current_user_id() AND actor.status = 'ACTIVE'
        WHERE j.id = job_status_log.job_id AND j.status = job_status_log.status
        AND ((job_status_log.status = 'ASSIGNED' AND financial_payer(r.property_id, j.owner_user_id)
                AND job_status_log.changed_by_user_id = j.owner_user_id)
            OR (job_status_log.status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED')
                AND j.fixer_user_id = actor.id AND job_status_log.changed_by_user_id = actor.id
                AND financial_actor_role('FIXER') AND EXISTS (SELECT 1 FROM fixer_profiles fp
                    WHERE fp.user_id = actor.id AND fp.verification_status = 'VERIFIED'))))
);

CREATE FUNCTION preserve_digital_payment_terms() RETURNS trigger
LANGUAGE plpgsql SET search_path = ${flyway:defaultSchema}, pg_temp AS $$
BEGIN
    IF ROW(NEW.id, NEW.quotation_id, NEW.request_id, NEW.owner_user_id, NEW.fixer_user_id,
           NEW.amount, NEW.payment_method, NEW.purpose, NEW.reference, NEW.created_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.quotation_id, OLD.request_id, OLD.owner_user_id, OLD.fixer_user_id,
           OLD.amount, OLD.payment_method, OLD.purpose, OLD.reference, OLD.created_at)
       OR (OLD.status = 'APPROVED' AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Canonical payment terms and an approved payment are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER preserve_digital_payment_terms BEFORE UPDATE ON repair_payments
    FOR EACH ROW EXECUTE FUNCTION preserve_digital_payment_terms();

-- jobs also serves aggregate workload reads. Preserve those existing reads, but constrain
-- actor-bound INSERTs to the canonical financial opening, including historical CASH compatibility.
CREATE FUNCTION authorize_financial_job_opening() RETURNS trigger
LANGUAGE plpgsql SET search_path = ${flyway:defaultSchema}, pg_temp AS $$
BEGIN
    IF current_user = 'fixup_app' AND (NEW.status <> 'ASSIGNED' OR NOT EXISTS (
        SELECT 1 FROM quotations q JOIN repair_requests r ON r.id = q.request_id
        WHERE q.id = NEW.quotation_id AND q.status = 'ACCEPTED' AND r.id = NEW.request_id
            AND q.fixer_user_id = NEW.fixer_user_id AND r.owner_user_id = NEW.owner_user_id
            AND q.amount = NEW.agreed_amount AND r.payment_method = NEW.payment_method
            AND financial_payer(r.property_id, r.owner_user_id)
            AND (NEW.payment_method = 'CASH' OR EXISTS (
                SELECT 1 FROM repair_payments p WHERE p.quotation_id = q.id AND p.request_id = r.id
                    AND p.owner_user_id = r.owner_user_id AND p.fixer_user_id = q.fixer_user_id
                    AND p.amount = q.amount AND p.purpose = 'SERVICE' AND p.payment_method = 'TRANSFER'
                    AND p.status = 'APPROVED')))) THEN
        RAISE EXCEPTION 'Job opening requires an authorized canonical payment' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER authorize_financial_job_opening BEFORE INSERT ON jobs
    FOR EACH ROW EXECUTE FUNCTION authorize_financial_job_opening();

-- No UPDATE/DELETE policies on contexts or attempts: application identities can only append/read.
GRANT SELECT, INSERT ON repair_payment_contexts TO fixup_app;
