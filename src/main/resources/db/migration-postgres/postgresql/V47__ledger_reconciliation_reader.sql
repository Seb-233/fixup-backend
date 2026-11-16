-- SAF-01: lectura de sistema para la conciliación del libro de pagos.
--
-- Las tablas de pagos tienen FORCE ROW LEVEL SECURITY: sin actor, un rol que no sea superusuario no
-- ve ninguna fila, y una conciliación sobre cero filas diría "cuadra". Por eso la conciliación no
-- depende de con qué usuario se conecta la aplicación: cambia, solo dentro de su transacción de
-- lectura, a este rol.
--
-- Mínimo privilegio, no una exención general:
--   * sin login, sin herencia, sin BYPASSRLS; RLS le sigue aplicando y lo que ve lo deciden las
--     políticas de abajo, una por tabla del libro;
--   * solo SELECT, y solo de las columnas que usan las reglas: montos, estados e identificadores.
--     Nada de users, propiedades, solicitudes, referencias de pago ni datos del proveedor;
--   * fixup_app (el rol de las peticiones de usuario) no es miembro.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'fixup_ledger_auditor') THEN
        CREATE ROLE fixup_ledger_auditor NOLOGIN NOINHERIT NOSUPERUSER
            NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles
               WHERE rolname = 'fixup_ledger_auditor'
                 AND (rolcanlogin OR rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole
                      OR rolreplication OR rolbypassrls))
       OR EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m
                  JOIN pg_catalog.pg_roles granted ON granted.oid = m.roleid
                  JOIN pg_catalog.pg_roles member ON member.oid = m.member
                  WHERE member.rolname = 'fixup_ledger_auditor'
                     OR (granted.rolname = 'fixup_ledger_auditor' AND member.rolname = 'fixup_app')) THEN
        RAISE EXCEPTION 'Unsafe configuration of the ledger reconciliation role';
    END IF;
END
$$;

-- Igual que fixup_app en V10: la conexión de la aplicación puede hacer SET LOCAL ROLE sin otra
-- contraseña ni otro datasource. Sin herencia: fuera de la conciliación, esa conexión no gana las
-- lecturas del auditor; solo las tiene mientras dura el SET LOCAL ROLE.
GRANT fixup_ledger_auditor TO CURRENT_USER WITH INHERIT FALSE, SET TRUE;

GRANT USAGE ON SCHEMA "${flyway:defaultSchema}" TO fixup_ledger_auditor;
GRANT SELECT (id, quotation_id, fixer_user_id, amount, purpose, status)
    ON "${flyway:defaultSchema}".repair_payments TO fixup_ledger_auditor;
GRANT SELECT (id, quotation_id, fixer_user_id, gross_amount, commission_amount, net_amount,
              commission_rate_bps, status, created_at, paid_out_at)
    ON "${flyway:defaultSchema}".fixer_earnings TO fixup_ledger_auditor;
GRANT SELECT (id, amount, earning_count, operation_key)
    ON "${flyway:defaultSchema}".payouts TO fixup_ledger_auditor;
GRANT SELECT (earning_id, payout_id, net_amount)
    ON "${flyway:defaultSchema}".payout_entries TO fixup_ledger_auditor;
GRANT SELECT (id, quotation_id, amount, status)
    ON "${flyway:defaultSchema}".owner_debts TO fixup_ledger_auditor;
GRANT SELECT (id, quotation_id, agreed_amount, received_amount, outstanding_amount)
    ON "${flyway:defaultSchema}".cash_settlements TO fixup_ledger_auditor;
GRANT SELECT (payment_id, resulting_status)
    ON "${flyway:defaultSchema}".simulated_payment_attempts TO fixup_ledger_auditor;
-- Fecha en que entró V41 (corte digital): separa los ingresos y transferencias históricos.
GRANT SELECT (version, installed_on)
    ON "${flyway:defaultSchema}".flyway_schema_history TO fixup_ledger_auditor;

CREATE POLICY ledger_audit_read ON "${flyway:defaultSchema}".repair_payments
    FOR SELECT TO fixup_ledger_auditor USING (true);
CREATE POLICY ledger_audit_read ON "${flyway:defaultSchema}".fixer_earnings
    FOR SELECT TO fixup_ledger_auditor USING (true);
CREATE POLICY ledger_audit_read ON "${flyway:defaultSchema}".payouts
    FOR SELECT TO fixup_ledger_auditor USING (true);
CREATE POLICY ledger_audit_read ON "${flyway:defaultSchema}".payout_entries
    FOR SELECT TO fixup_ledger_auditor USING (true);
CREATE POLICY ledger_audit_read ON "${flyway:defaultSchema}".owner_debts
    FOR SELECT TO fixup_ledger_auditor USING (true);
CREATE POLICY ledger_audit_read ON "${flyway:defaultSchema}".cash_settlements
    FOR SELECT TO fixup_ledger_auditor USING (true);
CREATE POLICY ledger_audit_read ON "${flyway:defaultSchema}".simulated_payment_attempts
    FOR SELECT TO fixup_ledger_auditor USING (true);
