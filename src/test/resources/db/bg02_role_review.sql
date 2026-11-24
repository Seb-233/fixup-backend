-- Ephemeral review fixture only. Privileged roles simulate the preflight; no runtime elevation.
CREATE ROLE fixup_app NOLOGIN NOBYPASSRLS;
CREATE ROLE fixup_identity_writer NOLOGIN NOBYPASSRLS;
CREATE ROLE fixup_green LOGIN PASSWORD 'isolated-owner-test' INHERIT SUPERUSER BYPASSRLS;
CREATE ROLE bg02_super_member NOLOGIN SUPERUSER NOBYPASSRLS;
CREATE ROLE bg02_bypass_member NOLOGIN NOSUPERUSER BYPASSRLS;
CREATE ROLE bg02_review_runtime LOGIN PASSWORD 'isolated-runtime-test' NOINHERIT NOBYPASSRLS;
GRANT fixup_app TO fixup_green, bg02_super_member, bg02_bypass_member WITH INHERIT TRUE, SET TRUE;
GRANT fixup_app TO bg02_review_runtime WITH INHERIT FALSE, SET TRUE;
CREATE TABLE users(id uuid PRIMARY KEY, status text NOT NULL, updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE user_roles(user_id uuid REFERENCES users, role text NOT NULL, PRIMARY KEY(user_id, role));
CREATE FUNCTION app_current_user_id() RETURNS uuid LANGUAGE sql STABLE AS
$$ SELECT nullif(current_setting('app.current_user_id', true), '')::uuid $$;
GRANT USAGE ON SCHEMA public TO fixup_app;
GRANT SELECT ON users, user_roles TO fixup_app;
GRANT UPDATE(updated_at) ON users TO fixup_app;
ALTER TABLE users OWNER TO fixup_green;
ALTER TABLE user_roles OWNER TO fixup_green;
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;
ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY users_read ON users FOR SELECT TO fixup_app USING (id = app_current_user_id());
CREATE POLICY users_lock ON users FOR UPDATE TO fixup_app USING (id = app_current_user_id()) WITH CHECK (id = app_current_user_id());
CREATE POLICY roles_directory ON user_roles FOR SELECT TO fixup_app USING (user_id = app_current_user_id());
CREATE POLICY roles_writer ON user_roles FOR INSERT TO fixup_identity_writer WITH CHECK (true);
INSERT INTO users(id, status) VALUES ('00000000-0000-0000-0000-000000000001', 'ACTIVE'), ('00000000-0000-0000-0000-000000000002', 'ACTIVE');
