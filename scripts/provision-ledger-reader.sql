-- SAF-01: ejecutar como administrador DESPUÉS de V48, en la base de la aplicación.
-- El nombre del login se proporciona en esta misma sesión mediante:
--   SET fixup.ledger_runtime_role = 'runtime_login';
-- No contiene credenciales ni nombres de Blue/Green. Un DO es atómico y se puede repetir.
DO $$
DECLARE
    runtime_name text := current_setting('fixup.ledger_runtime_role', true);
    runtime_oid oid;
BEGIN
    IF runtime_name IS NULL OR btrim(runtime_name) = '' THEN
        RAISE EXCEPTION 'Set fixup.ledger_runtime_role to the application runtime login';
    END IF;
    SELECT oid INTO runtime_oid FROM pg_catalog.pg_roles
        WHERE rolname = runtime_name AND rolcanlogin
          AND NOT (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication);
    IF runtime_oid IS NULL OR runtime_name IN (current_user, session_user) THEN
        RAISE EXCEPTION 'The ledger runtime must be a separate restricted login';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'fixup_ledger_auditor')
       OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_policies
                      WHERE schemaname = current_schema() AND tablename = 'repair_payments'
                        AND policyname = 'ledger_audit_read') THEN
        RAISE EXCEPTION 'Apply V48 in the current schema before provisioning the ledger runtime';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'fixup_ledger_auditor'
                AND (rolcanlogin OR rolinherit OR rolsuper OR rolbypassrls OR rolcreatedb
                     OR rolcreaterole OR rolreplication))
       OR pg_catalog.pg_has_role('fixup_app', 'fixup_ledger_auditor', 'MEMBER')
       OR EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m
                   JOIN pg_catalog.pg_roles r ON r.oid = m.member
                   WHERE r.rolname = 'fixup_ledger_auditor') THEN
        RAISE EXCEPTION 'Unsafe configuration of the ledger reconciliation role';
    END IF;
    IF pg_catalog.has_schema_privilege(runtime_oid, current_schema(), 'CREATE')
       OR EXISTS (SELECT 1 FROM pg_catalog.pg_class c
                   JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                   WHERE n.nspname = current_schema() AND c.relowner = runtime_oid) THEN
        RAISE EXCEPTION 'The ledger runtime must not own database objects or have schema CREATE';
    END IF;
    -- %I cita el identificador: el parámetro no se concatena como SQL ejecutable.
    EXECUTE format('GRANT fixup_ledger_auditor TO %I WITH ADMIN FALSE, INHERIT FALSE, SET TRUE', runtime_name);
END
$$;
