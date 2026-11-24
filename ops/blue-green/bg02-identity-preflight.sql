-- Read-only preflight; run only through an approved administrative connection.
-- PostgreSQL >= 16 (membership SET/INHERIT options). No credentials in this file.
BEGIN READ ONLY;
SELECT rolname, rolcanlogin, rolinherit, rolsuper, rolbypassrls, rolcreatedb, rolcreaterole, rolreplication
FROM pg_roles WHERE rolname IN ('fixup_green_runtime', 'fixup_app', 'fixup_identity', 'fixup_identity_writer');
SELECT parent.rolname AS granted_role, member.rolname AS member_role,
       m.admin_option, m.inherit_option, m.set_option
FROM pg_auth_members m JOIN pg_roles parent ON parent.oid = m.roleid
JOIN pg_roles member ON member.oid = m.member
WHERE member.rolname IN ('fixup_green_runtime', 'fixup_app', 'fixup_identity');
SELECT schemaname, tablename, policyname, permissive, roles, cmd, qual, with_check
FROM pg_policies WHERE schemaname = 'public' AND tablename IN ('users', 'user_roles')
ORDER BY tablename, policyname;
SELECT c.relname, pg_get_userbyid(c.relowner) AS owner, c.relrowsecurity, c.relforcerowsecurity, c.relacl
FROM pg_class c WHERE c.oid IN ('public.users'::regclass, 'public.user_roles'::regclass);
SELECT attrelid::regclass AS relation, attname, attacl FROM pg_attribute
WHERE attrelid IN ('public.users'::regclass, 'public.user_roles'::regclass) AND attnum > 0 AND NOT attisdropped;
SELECT defaclrole::regrole, defaclnamespace::regnamespace, defaclobjtype, defaclacl FROM pg_default_acl;
SELECT installed_rank, version, description, checksum, success FROM public.flyway_schema_history ORDER BY installed_rank;

DO $$ DECLARE t text; privilege_name text; expected_role text; BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fixup_green_runtime' AND rolcanlogin
        AND NOT (rolinherit OR rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication)) THEN
        RAISE EXCEPTION 'Unsafe or missing Green runtime role';
    END IF;
    FOREACH expected_role IN ARRAY ARRAY['fixup_app', 'fixup_identity'] LOOP
        IF NOT EXISTS (SELECT FROM pg_auth_members m JOIN pg_roles parent ON parent.oid = m.roleid
            JOIN pg_roles member ON member.oid = m.member
            WHERE member.rolname = 'fixup_green_runtime' AND parent.rolname = expected_role
            AND m.set_option AND NOT m.inherit_option AND NOT m.admin_option) THEN
            RAISE EXCEPTION 'Missing safe SET-only membership: %', expected_role;
        END IF;
    END LOOP;
    FOREACH t IN ARRAY ARRAY['public.users', 'public.user_roles'] LOOP
        FOREACH privilege_name IN ARRAY ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER'] LOOP
            IF has_table_privilege('fixup_green_runtime', t, privilege_name) THEN
                RAISE EXCEPTION 'Unexpected base runtime privilege % on %', privilege_name, t;
            END IF;
        END LOOP;
        FOREACH privilege_name IN ARRAY ARRAY['SELECT', 'INSERT', 'UPDATE', 'REFERENCES'] LOOP
            IF has_any_column_privilege('fixup_green_runtime', t, privilege_name) THEN
                RAISE EXCEPTION 'Unexpected base runtime column privilege % on %', privilege_name, t;
            END IF;
        END LOOP;
    END LOOP;
END $$;
ROLLBACK;
