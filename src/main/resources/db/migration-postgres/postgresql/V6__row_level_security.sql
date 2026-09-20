-- FR-UC-25: aislamiento de datos entre cuentas mediante Row-Level Security nativo de PostgreSQL.
--
-- Esta migracion es especifica de PostgreSQL (roles, set_config, CREATE POLICY) y por eso vive bajo
-- db/migration-postgres/{vendor}: Flyway solo la resuelve cuando la conexion real es PostgreSQL, y
-- nunca contra el H2 en memoria que usan las pruebas de contrato HTTP (application-test.yml). RLS no
-- existe en H2, de ahi que su verificacion dependa de Testcontainers (ver PostgresRowLevelSecurityIT).
--
-- Modelo de confianza:
--   1. CurrentActorProvider (identityaccess) resuelve identidad+roles contra users/user_roles en cada
--      peticion. El backend nunca reenvia el rol tal como llega del token.
--   2. RlsSessionTransactionManager (com.fixup.shared.configuration) publica ese actor como variables
--      de sesion (app.current_user_id, app.current_user_roles) con set_config(..., true) -equivalente
--      a SET LOCAL- al iniciar cada transaccion que tiene un actor autenticado.
--   3. La misma transaccion cambia a "SET LOCAL ROLE fixup_app": ni un superusuario ni el dueño de las
--      tablas quedan nunca sujetos a RLS en PostgreSQL, así que el trafico de aplicacion nunca puede
--      correr con esos privilegios si se quiere que las politicas de abajo tengan efecto real.
--   4. Trabajo sin actor (Flyway, jobs de sistema, limpieza de tests) sigue la conexion original: RLS
--      es una barrera para peticiones de usuario, no para procesos internos de confianza.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fixup_app') THEN
        CREATE ROLE fixup_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;

-- Permite que la conexion con la que corre la aplicacion (la misma que ejecuta esta migracion) pueda
-- hacer SET ROLE fixup_app sin necesitar una contraseña ni un datasource adicional.
GRANT fixup_app TO CURRENT_USER;

GRANT USAGE ON SCHEMA public TO fixup_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO fixup_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO fixup_app;

-- Unico punto de lectura de las variables de sesion. Cualquier tabla nueva (incluidas las de chat de
-- FR-UC-24) debe reusar estas funciones en sus propias politicas en vez de releer current_setting.
CREATE OR REPLACE FUNCTION app_current_user_id() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT NULLIF(current_setting('app.current_user_id', true), '')::uuid
$$;

CREATE OR REPLACE FUNCTION app_current_user_has_role(role_name text) RETURNS boolean
    LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT role_name = ANY(string_to_array(NULLIF(current_setting('app.current_user_roles', true), ''), ','))
$$;

-- repair_requests (modulo requests): el propietario/arrendatario dueño, el fixer asignado, cualquier
-- fixer activo mientras la solicitud sigue OPEN (bandeja de FR-UC-18 y el SELECT ... FOR UPDATE que
-- hace SubmitQuotation antes de cotizar) y PLATFORM_ADMIN sin restriccion.
ALTER TABLE repair_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE repair_requests FORCE ROW LEVEL SECURITY;

CREATE POLICY repair_requests_select ON repair_requests FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR assigned_fixer_user_id = app_current_user_id()
        OR (status = 'OPEN' AND app_current_user_has_role('FIXER'))
    );

CREATE POLICY repair_requests_insert ON repair_requests FOR INSERT
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
    );

-- USING is deliberately wider than WITH CHECK: PostgreSQL requires a row to pass the UPDATE policy's
-- USING clause (in addition to SELECT) for "SELECT ... FOR UPDATE" to lock it, because that lock is
-- what an UPDATE would take. SubmitQuotation locks the OPEN request before cotizando, as any eligible
-- fixer, before it ever becomes theirs -- so that lock must be allowed here even though such a fixer
-- must never be able to actually change the row: WITH CHECK stays limited to owner/assigned/admin, so
-- a real write attempt from an unrelated fixer still fails, just as a policy violation instead of a
-- silently-empty update.
CREATE POLICY repair_requests_update ON repair_requests FOR UPDATE
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR assigned_fixer_user_id = app_current_user_id()
        OR (status = 'OPEN' AND app_current_user_has_role('FIXER'))
    )
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR assigned_fixer_user_id = app_current_user_id()
    );

-- quotations (modulo quotations): el fixer que la envio, el dueño de la solicitud a la que responde
-- (compara/acepta/rechaza ofertas, incluidas las de otros fixers dentro de la misma decision de
-- AcceptQuotation) y PLATFORM_ADMIN.
ALTER TABLE quotations ENABLE ROW LEVEL SECURITY;
ALTER TABLE quotations FORCE ROW LEVEL SECURITY;

CREATE POLICY quotations_select ON quotations FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR fixer_user_id = app_current_user_id()
        OR EXISTS (
            SELECT 1 FROM repair_requests r
            WHERE r.id = quotations.request_id AND r.owner_user_id = app_current_user_id()
        )
    );

CREATE POLICY quotations_insert ON quotations FOR INSERT
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR fixer_user_id = app_current_user_id()
    );

CREATE POLICY quotations_update ON quotations FOR UPDATE
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR fixer_user_id = app_current_user_id()
        OR EXISTS (
            SELECT 1 FROM repair_requests r
            WHERE r.id = quotations.request_id AND r.owner_user_id = app_current_user_id()
        )
    )
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR fixer_user_id = app_current_user_id()
        OR EXISTS (
            SELECT 1 FROM repair_requests r
            WHERE r.id = quotations.request_id AND r.owner_user_id = app_current_user_id()
        )
    );

-- FR-UC-24 (mensajeria) todavia no existe: el modulo messaging es un placeholder sin tablas. Cuando
-- se cree la tabla de chat, habilitarle RLS igual que arriba y reusar app_current_user_id()/
-- app_current_user_has_role() en sus propias politicas en lugar de reinventar la lectura de sesion.
