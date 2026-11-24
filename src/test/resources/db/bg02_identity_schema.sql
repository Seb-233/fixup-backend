-- Confirmed Green ACL boundary, applied after base migrations in this ephemeral database only.
-- Identity SELECT predicates are the confirmed USING(true), deliberately NOT subject-restricted.
-- The remaining Green predicate text was not supplied: self-only predicates below are conservative
-- dependencies, not a claim to reproduce their exact pg_policies expressions.
GRANT USAGE ON SCHEMA public TO fixup_identity;
REVOKE ALL PRIVILEGES ON users, user_roles FROM fixup_app;
GRANT SELECT ON users, user_roles TO fixup_app, fixup_identity;
GRANT UPDATE(updated_at) ON users TO fixup_app;
GRANT INSERT ON users TO fixup_identity;
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;
ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY users_identity_read ON users FOR SELECT TO fixup_identity USING (true);
CREATE POLICY roles_identity_read ON user_roles FOR SELECT TO fixup_identity USING (true);
CREATE POLICY fixture_identity_insert ON users FOR INSERT TO fixup_identity
    WITH CHECK (auth0_subject = nullif(current_setting('app.identity_subject', true), '') AND status = 'ACTIVE');
CREATE POLICY fixture_users_read ON users FOR SELECT TO fixup_app
    USING (id = app_current_user_id());
CREATE POLICY users_lock ON users FOR UPDATE TO fixup_app
    USING (id = app_current_user_id()) WITH CHECK (id = app_current_user_id());
CREATE POLICY roles_directory ON user_roles FOR SELECT TO fixup_app
    USING (user_id = app_current_user_id());
CREATE POLICY roles_writer ON user_roles FOR INSERT TO fixup_identity_writer
    WITH CHECK (user_id = app_current_user_id() AND role IN ('OWNER', 'TENANT', 'FIXER'));
