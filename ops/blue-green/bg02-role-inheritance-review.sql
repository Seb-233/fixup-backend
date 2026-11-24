-- READ ONLY. Prepared for an authorized reviewer using an EXISTING snapshot/approved session.
-- This review does not authorize querying Green. PostgreSQL >= 16. No role changes.
BEGIN READ ONLY;
-- Include direct and transitive membership, even SET-only paths (not policy inheritance).
WITH RECURSIVE paths AS (
    SELECT m.member AS member_oid, ARRAY[m.roleid, m.member] AS path,
           m.inherit_option AS inherits, m.set_option AS can_set
    FROM pg_auth_members m WHERE m.roleid = 'fixup_app'::regrole
    UNION ALL
    SELECT m.member, p.path || m.member,
           p.inherits AND m.inherit_option, p.can_set AND m.set_option
    FROM paths p JOIN pg_auth_members m ON m.roleid = p.member_oid
    WHERE NOT m.member = ANY(p.path)
)
SELECT r.rolname, r.rolsuper, r.rolbypassrls, r.rolcanlogin, r.rolinherit,
       bool_or(p.inherits) AS inherits_via_path, bool_or(p.can_set) AS set_via_path,
       pg_has_role(r.oid, 'fixup_app', 'USAGE') AS effective_usage,
       pg_has_role(r.oid, 'fixup_app', 'SET') AS effective_set,
       r.oid IN (SELECT relowner FROM pg_class
           WHERE oid IN ('public.users'::regclass, 'public.user_roles'::regclass)) AS owns_identity_table
FROM paths p JOIN pg_roles r ON r.oid = p.member_oid
GROUP BY r.oid, r.rolname, r.rolsuper, r.rolbypassrls, r.rolcanlogin, r.rolinherit
ORDER BY r.rolname;
-- Attributes such as SUPERUSER/BYPASSRLS are NOT inherited. USAGE for a superuser
-- may report true without a membership path; inspect all such roles separately.
SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolsuper OR rolbypassrls ORDER BY rolname;
SELECT c.relname, pg_get_userbyid(c.relowner) AS owner, c.relrowsecurity, c.relforcerowsecurity
FROM pg_class c WHERE c.oid IN ('public.users'::regclass, 'public.user_roles'::regclass);
SELECT schemaname, tablename, policyname, permissive, roles, cmd, qual, with_check
FROM pg_policies WHERE schemaname = 'public' AND tablename IN ('users', 'user_roles')
ORDER BY tablename, policyname;
ROLLBACK;
