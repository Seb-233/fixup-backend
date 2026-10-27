CREATE FUNCTION enforce_digital_creation() RETURNS trigger
LANGUAGE plpgsql SET search_path = ${flyway:defaultSchema}, pg_temp AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.payment_method <> 'TRANSFER' OR NEW.legacy_cash THEN
            RAISE EXCEPTION 'DIGITAL_PAYMENT_REQUIRED' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.payment_method IS DISTINCT FROM OLD.payment_method OR NEW.legacy_cash IS DISTINCT FROM OLD.legacy_cash THEN
        RAISE EXCEPTION 'Historical payment method and origin are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER enforce_digital_creation BEFORE INSERT OR UPDATE ON repair_requests FOR EACH ROW EXECUTE FUNCTION enforce_digital_creation();
CREATE TRIGGER enforce_digital_creation BEFORE INSERT OR UPDATE ON jobs FOR EACH ROW EXECUTE FUNCTION enforce_digital_creation();
CREATE FUNCTION require_historical_cash_job() RETURNS trigger
LANGUAGE plpgsql SET search_path = ${flyway:defaultSchema}, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM jobs j WHERE j.id = NEW.job_id AND j.legacy_cash AND j.payment_method = 'CASH') THEN
        RAISE EXCEPTION 'Only a pre-cutover CASH job can settle cash' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER require_historical_cash_job BEFORE INSERT ON cash_settlements FOR EACH ROW EXECUTE FUNCTION require_historical_cash_job();

-- No dependency from payments to jobs in Java: SQL verifies the already coordinated facts.
CREATE FUNCTION earning_approved_payer(quote uuid, beneficiary uuid, gross bigint) RETURNS boolean
LANGUAGE sql STABLE SET search_path = ${flyway:defaultSchema}, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM repair_payments p LEFT JOIN repair_payment_contexts c ON c.quotation_id = p.quotation_id
        WHERE p.quotation_id = quote AND p.fixer_user_id = beneficiary AND p.amount = gross AND p.status = 'APPROVED'
            AND financial_payer(c.property_id, p.owner_user_id))
$$;
ALTER TABLE fixer_earnings ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixer_earnings FORCE ROW LEVEL SECURITY;
CREATE POLICY earnings_select ON fixer_earnings FOR SELECT TO fixup_app USING (
    (financial_actor_role('FIXER') AND fixer_user_id = app_current_user_id())
    OR (status IN ('HELD', 'AVAILABLE') AND earning_approved_payer(quotation_id, fixer_user_id, gross_amount))
);
CREATE POLICY earnings_insert ON fixer_earnings FOR INSERT TO fixup_app WITH CHECK (
    earning_approved_payer(quotation_id, fixer_user_id, gross_amount)
    AND commission_rate_bps = 1000 AND commission_amount = gross_amount / 10
    AND paid_out_at IS NULL
    AND EXISTS (SELECT 1 FROM repair_payments p WHERE p.quotation_id = fixer_earnings.quotation_id
        AND p.status = 'APPROVED' AND p.fixer_user_id = fixer_earnings.fixer_user_id AND p.amount = fixer_earnings.gross_amount
        AND fixer_earnings.created_at = p.completed_at AND fixer_earnings.updated_at = p.completed_at
        AND ((p.purpose = 'SERVICE' AND fixer_earnings.status = 'HELD' AND fixer_earnings.released_at IS NULL)
            OR (p.purpose = 'OUTSTANDING_BALANCE' AND fixer_earnings.status = 'AVAILABLE' AND fixer_earnings.released_at = p.completed_at)))
);
CREATE POLICY earnings_update ON fixer_earnings FOR UPDATE TO fixup_app
    USING (financial_actor_role('FIXER') AND fixer_user_id = app_current_user_id())
    WITH CHECK (financial_actor_role('FIXER') AND fixer_user_id = app_current_user_id());

ALTER TABLE payouts ENABLE ROW LEVEL SECURITY;
ALTER TABLE payouts FORCE ROW LEVEL SECURITY;
CREATE POLICY payouts_select ON payouts FOR SELECT TO fixup_app
    USING (financial_actor_role('FIXER') AND fixer_user_id = app_current_user_id());
CREATE POLICY payouts_insert ON payouts FOR INSERT TO fixup_app WITH CHECK (
    financial_actor_role('FIXER') AND fixer_user_id = app_current_user_id()
    AND operation_key IS NOT NULL AND status = 'TRANSFERRED' AND transferred_at IS NOT NULL
);
ALTER TABLE payout_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE payout_entries FORCE ROW LEVEL SECURITY;
CREATE POLICY payout_entries_select ON payout_entries FOR SELECT TO fixup_app
    USING (financial_actor_role('FIXER') AND fixer_user_id = app_current_user_id());
CREATE POLICY payout_entries_insert ON payout_entries FOR INSERT TO fixup_app
    WITH CHECK (financial_actor_role('FIXER') AND fixer_user_id = app_current_user_id());
GRANT SELECT, INSERT ON payout_entries TO fixup_app;

CREATE FUNCTION check_payout_entry() RETURNS trigger
LANGUAGE plpgsql SET search_path = ${flyway:defaultSchema}, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN RAISE EXCEPTION 'Payout composition is immutable' USING ERRCODE = '23514'; END IF;
    PERFORM 1 FROM fixer_earnings e WHERE e.id = NEW.earning_id AND e.fixer_user_id = NEW.fixer_user_id
        AND e.status = 'AVAILABLE' AND e.net_amount = NEW.net_amount FOR UPDATE;
    IF NOT FOUND OR NOT EXISTS (SELECT 1 FROM payouts p WHERE p.id = NEW.payout_id
        AND p.fixer_user_id = NEW.fixer_user_id AND p.operation_key IS NOT NULL AND p.status = 'TRANSFERRED') THEN
        RAISE EXCEPTION 'Payout entry requires an available earning and canonical payout' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER check_payout_entry BEFORE INSERT OR UPDATE ON payout_entries FOR EACH ROW EXECUTE FUNCTION check_payout_entry();

CREATE FUNCTION preserve_earning_and_transition() RETURNS trigger
LANGUAGE plpgsql SET search_path = ${flyway:defaultSchema}, pg_temp AS $$
BEGIN
    IF ROW(NEW.id,NEW.quotation_id,NEW.fixer_user_id,NEW.gross_amount,NEW.commission_amount,NEW.net_amount,NEW.commission_rate_bps,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.quotation_id,OLD.fixer_user_id,OLD.gross_amount,OLD.commission_amount,OLD.net_amount,OLD.commission_rate_bps,OLD.created_at) THEN
        RAISE EXCEPTION 'Earning financial terms are immutable' USING ERRCODE = '23514';
    END IF;
    IF current_user = 'fixup_app' AND NEW IS DISTINCT FROM OLD THEN
        IF OLD.status = 'HELD' AND NEW.status = 'AVAILABLE' THEN
            IF NEW.paid_out_at IS NOT NULL OR NEW.updated_at IS DISTINCT FROM NEW.released_at
                OR NOT EXISTS (SELECT 1 FROM jobs j WHERE j.quotation_id = NEW.quotation_id AND j.fixer_user_id = NEW.fixer_user_id
                AND j.status = 'COMPLETED' AND j.completed_at = NEW.released_at)
                OR NOT EXISTS (SELECT 1 FROM fixer_profiles f WHERE f.user_id = NEW.fixer_user_id AND f.verification_status = 'VERIFIED') THEN
                RAISE EXCEPTION 'Earning release requires completed job' USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.status = 'AVAILABLE' AND NEW.status = 'PAID_OUT' THEN
            IF NEW.updated_at IS DISTINCT FROM NEW.paid_out_at OR NEW.released_at IS DISTINCT FROM OLD.released_at OR NOT EXISTS (
                SELECT 1 FROM payout_entries d JOIN payouts p ON p.id = d.payout_id
                WHERE d.earning_id = NEW.id AND d.net_amount = NEW.net_amount AND p.transferred_at = NEW.paid_out_at) THEN
                RAISE EXCEPTION 'Earning payout requires immutable payout entry' USING ERRCODE = '23514';
            END IF;
        ELSE RAISE EXCEPTION 'Invalid earning transition' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER preserve_earning_and_transition BEFORE UPDATE ON fixer_earnings FOR EACH ROW EXECUTE FUNCTION preserve_earning_and_transition();

CREATE FUNCTION check_payout_balance() RETURNS trigger
LANGUAGE plpgsql SET search_path = ${flyway:defaultSchema}, pg_temp AS $$
DECLARE payout_key uuid; p payouts%ROWTYPE; total numeric; included integer; paid integer;
BEGIN
    IF TG_TABLE_NAME = 'payouts' THEN payout_key := NEW.id; ELSE payout_key := NEW.payout_id; END IF;
    SELECT * INTO p FROM payouts WHERE id = payout_key;
    IF p.operation_key IS NULL THEN RETURN NULL; END IF;
    SELECT COALESCE(sum(d.net_amount),0),count(*),count(*) FILTER (WHERE e.status = 'PAID_OUT' AND e.paid_out_at = p.transferred_at)
        INTO total,included,paid FROM payout_entries d JOIN fixer_earnings e ON e.id = d.earning_id WHERE d.payout_id = p.id;
    IF total <> p.amount OR included <> p.earning_count OR paid <> included THEN
        RAISE EXCEPTION 'Payout total, membership and paid earnings must agree' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER payout_balance AFTER INSERT ON payouts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_payout_balance();
CREATE CONSTRAINT TRIGGER payout_entry_balance AFTER INSERT ON payout_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_payout_balance();
