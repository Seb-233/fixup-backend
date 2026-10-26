-- I-08: a boolean debt check for an authorized reporter, without granting the reporter debt rows.
-- This role owns only the function below: no login, memberships, bypass-RLS, DML or schema creation.
-- A role-specific SELECT policy supplies the function's narrow read; all historical policies stay
-- intact and fixup_app is deliberately NOT a member. The trusted actor GUC is the V10 trust model.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'fixup_property_debt_reader') THEN
        CREATE ROLE fixup_property_debt_reader NOLOGIN NOINHERIT NOSUPERUSER
            NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles
               WHERE rolname = 'fixup_property_debt_reader'
                 AND (rolcanlogin OR rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole
                      OR rolreplication OR rolbypassrls))
       OR EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m
                  JOIN pg_catalog.pg_roles r ON r.oid = m.roleid OR r.oid = m.member
                  WHERE r.rolname = 'fixup_property_debt_reader') THEN
        RAISE EXCEPTION 'Unsafe configuration of the property debt check role';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA "${flyway:defaultSchema}" TO fixup_property_debt_reader;
GRANT SELECT (id, status) ON "${flyway:defaultSchema}".users TO fixup_property_debt_reader;
GRANT SELECT (user_id, role) ON "${flyway:defaultSchema}".user_roles TO fixup_property_debt_reader;
GRANT SELECT (id, owner_user_id, manager_user_id) ON "${flyway:defaultSchema}".properties TO fixup_property_debt_reader;
GRANT SELECT (property_id, tenant_user_id, status, start_date, end_date)
    ON "${flyway:defaultSchema}".rental_contracts TO fixup_property_debt_reader;
GRANT SELECT (owner_user_id, status) ON "${flyway:defaultSchema}".owner_debts TO fixup_property_debt_reader;

CREATE POLICY owner_debts_property_check ON "${flyway:defaultSchema}".owner_debts
    FOR SELECT TO fixup_property_debt_reader USING (true);

CREATE FUNCTION "${flyway:defaultSchema}".app_property_owner_has_outstanding_debt(
    p_property_id uuid, p_expected_owner_user_id uuid)
RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET row_security = on
AS $$
DECLARE
    actor_id uuid := "${flyway:defaultSchema}".app_current_user_id();
    today_bogota date := (CURRENT_TIMESTAMP AT TIME ZONE 'America/Bogota')::date;
BEGIN
    -- One indistinguishable rejection for missing identity/property, wrong owner or no relationship.
    -- Roles/status are checked in the database as well as in the request application service.
    IF actor_id IS NULL OR p_property_id IS NULL OR p_expected_owner_user_id IS NULL
       OR NOT EXISTS (
           SELECT 1
           FROM "${flyway:defaultSchema}".properties p
           JOIN "${flyway:defaultSchema}".users u ON u.id = actor_id AND u.status = 'ACTIVE'
           WHERE p.id = p_property_id AND p.owner_user_id = p_expected_owner_user_id
             AND EXISTS (
                 SELECT 1 FROM "${flyway:defaultSchema}".user_roles r
                 WHERE r.user_id = actor_id AND (
                     (r.role = 'OWNER' AND p.owner_user_id = actor_id)
                     OR (r.role = 'REAL_ESTATE_MANAGER' AND p.manager_user_id = actor_id)
                     OR (r.role = 'TENANT' AND EXISTS (
                         SELECT 1 FROM "${flyway:defaultSchema}".rental_contracts c
                         WHERE c.property_id = p.id AND c.tenant_user_id = actor_id
                           AND c.status IN ('ACTIVE', 'RENEWED')
                           AND c.start_date <= today_bogota AND c.end_date >= today_bogota
                     ))
                 )
             )
       ) THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Property debt check is not authorized';
    END IF;

    RETURN EXISTS (
        SELECT 1 FROM "${flyway:defaultSchema}".owner_debts d
        WHERE d.owner_user_id = p_expected_owner_user_id AND d.status = 'OUTSTANDING'
    );
END
$$;

-- Flyway applies this migration transactionally: there is no PUBLIC execution window.
REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".app_property_owner_has_outstanding_debt(uuid, uuid) FROM PUBLIC;
ALTER FUNCTION "${flyway:defaultSchema}".app_property_owner_has_outstanding_debt(uuid, uuid)
    OWNER TO fixup_property_debt_reader;
GRANT EXECUTE ON FUNCTION "${flyway:defaultSchema}".app_property_owner_has_outstanding_debt(uuid, uuid) TO fixup_app;
