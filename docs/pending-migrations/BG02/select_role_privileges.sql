-- PROPOSAL ONLY: outside Flyway; no version allocated. NEVER run on Green in this iteration.
-- Execute as migration owner, in ONE transaction; roll back on any error.
-- Dependencies: existing SELECT users/user_roles, UPDATE(users.updated_at), schema USAGE,
-- EXECUTE public.app_current_user_id(), and users SELECT/UPDATE policies permitting the
-- actor's row (users_lock supports FOR UPDATE). app_current_user_id() must return the
-- transaction-local, server-resolved UUID, or NULL without an actor. No JWT roles are trusted.
-- The ACTIVE check runs as fixup_app through users SELECT RLS; hidden/missing rows deny.
-- Existing identity reads USING(true) and all identity_writer policies/ACLs remain unchanged.
-- Both INSERT policies below are necessary: permissive policies combine with OR;
-- the RESTRICTIVE guard combines with AND, so another permissive policy (even PUBLIC/ALL)
-- cannot authorize another user, an administrative role, or an inactive account.
-- Confirmed Green: the only other effective app inheritor is fixup_green, which
-- currently has SUPERUSER/BYPASSRLS; FORCE RLS does not constrain that effective role.
-- SET ROLE app removes this bypass: never exempt session_user or a table owner here.
-- Future non-bypass inheritors must be reviewed, not silently admitted. Inventory SQL:
-- ops/blue-green/bg02-role-inheritance-review.sql (read-only, requires authorization).
-- Coordinate Flyway series/checksums with develop before allocating a migration version.
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fixup_app' AND
        NOT (rolsuper OR rolbypassrls OR rolcanlogin OR rolcreaterole OR rolcreatedb OR rolreplication)) THEN
        RAISE EXCEPTION 'Unsafe or missing application role';
    END IF;
    -- A restrictive policy TO app also affects roles inheriting app. Refuse to alter the
    -- effective writer behavior indirectly; review such a role graph separately.
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'fixup_identity_writer') THEN
        IF pg_has_role('fixup_identity_writer', 'fixup_app', 'USAGE') THEN
            RAISE EXCEPTION 'identity_writer inherits app; review policy impact separately';
        END IF;
    END IF;
    IF EXISTS (SELECT FROM pg_roles r WHERE r.rolname <> 'fixup_app'
        AND NOT (r.rolsuper OR r.rolbypassrls)
        AND pg_has_role(r.oid, 'fixup_app', 'USAGE')) THEN
        RAISE EXCEPTION 'Unreviewed non-bypass app inheritor; review restrictive policy impact';
    END IF;
    IF NOT has_table_privilege('fixup_app', 'public.users', 'SELECT') OR
       NOT has_table_privilege('fixup_app', 'public.user_roles', 'SELECT') OR
       NOT has_column_privilege('fixup_app', 'public.users', 'updated_at', 'UPDATE') OR
       NOT has_schema_privilege('fixup_app', 'public', 'USAGE') OR
       NOT has_function_privilege('fixup_app', 'public.app_current_user_id()', 'EXECUTE') THEN
        RAISE EXCEPTION 'Missing existing Green dependencies; do not broaden grants here';
    END IF;
    IF EXISTS (SELECT FROM pg_attribute WHERE attrelid = 'public.users'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname <> 'updated_at'
        AND has_column_privilege('fixup_app', attrelid, attnum, 'UPDATE')) OR
       has_any_column_privilege('fixup_app', 'public.users', 'INSERT') OR
       has_table_privilege('fixup_app', 'public.users', 'DELETE,TRUNCATE') OR
       has_table_privilege('fixup_app', 'public.user_roles', 'INSERT,DELETE,TRUNCATE') OR
       has_any_column_privilege('fixup_app', 'public.user_roles', 'UPDATE') OR
       EXISTS (SELECT FROM pg_attribute WHERE attrelid = 'public.user_roles'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname NOT IN ('user_id', 'role')
        AND has_column_privilege('fixup_app', attrelid, attnum, 'INSERT')) THEN
        RAISE EXCEPTION 'Unexpected effective write privileges; audit before granting';
    END IF;
    IF (SELECT count(*) FROM pg_class WHERE oid IN ('public.users'::regclass, 'public.user_roles'::regclass)
        AND relrowsecurity AND relforcerowsecurity) <> 2 THEN
        RAISE EXCEPTION 'Identity tables must retain ENABLE/FORCE RLS';
    END IF;
END $$;

CREATE POLICY roles_app_self_insert ON public.user_roles AS PERMISSIVE
    FOR INSERT TO fixup_app
    WITH CHECK (user_id = public.app_current_user_id()
        AND role IN ('OWNER', 'TENANT', 'FIXER')
        AND EXISTS (SELECT FROM public.users u
            WHERE u.id = public.app_current_user_id() AND u.status = 'ACTIVE'));

CREATE POLICY roles_app_self_insert_guard ON public.user_roles AS RESTRICTIVE
    FOR INSERT TO fixup_app
    WITH CHECK (user_id = public.app_current_user_id()
        AND role IN ('OWNER', 'TENANT', 'FIXER')
        AND EXISTS (SELECT FROM public.users u
            WHERE u.id = public.app_current_user_id() AND u.status = 'ACTIVE'));

GRANT INSERT(user_id, role) ON public.user_roles TO fixup_app;
