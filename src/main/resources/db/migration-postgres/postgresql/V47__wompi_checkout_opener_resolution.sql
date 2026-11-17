-- INT-01: resolución acotada "referencia de checkout -> quién lo abrió" para el webhook de Wompi.
--
-- El webhook llega sin usuario: antes de aplicar el resultado necesita saber con qué identidad
-- hacerlo. wompi_checkouts tiene FORCE ROW LEVEL SECURITY y sus políticas (V46) exigen un actor, así
-- que una consulta directa sin actor solo devuelve filas si la conexión es superusuario o tiene
-- BYPASSRLS. El runtime de Green no tiene ninguna de las dos cosas: no encontraría el checkout y el
-- pago quedaría sin registrar.
--
-- No se abre SELECT a nadie más. Igual que en V38, un rol sin login, sin herencia, sin membresías y
-- sin BYPASSRLS es dueño de una sola función SECURITY DEFINER; ese rol solo lee dos columnas
-- (referencia y quién la abrió) mediante una política propia. La función:
--   * solo responde a una sesión sin actor (el proceso del webhook); una sesión de usuario no puede
--     usarla para resolver referencias de otros;
--   * devuelve únicamente el id de quien abrió el checkout, nada del pago ni del monto.
-- El resultado del evento se sigue aplicando después con ese actor, su rol fixup_app y las políticas
-- de V46 (ver PayWithWompi.onEvent).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'fixup_wompi_checkout_resolver') THEN
        CREATE ROLE fixup_wompi_checkout_resolver NOLOGIN NOINHERIT NOSUPERUSER
            NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles
               WHERE rolname = 'fixup_wompi_checkout_resolver'
                 AND (rolcanlogin OR rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole
                      OR rolreplication OR rolbypassrls))
       OR EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m
                  JOIN pg_catalog.pg_roles r ON r.oid = m.roleid OR r.oid = m.member
                  WHERE r.rolname = 'fixup_wompi_checkout_resolver') THEN
        RAISE EXCEPTION 'Unsafe configuration of the Wompi checkout resolver role';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA "${flyway:defaultSchema}" TO fixup_wompi_checkout_resolver;
GRANT SELECT (reference, created_by_user_id)
    ON "${flyway:defaultSchema}".wompi_checkouts TO fixup_wompi_checkout_resolver;

CREATE POLICY wompi_checkouts_webhook_resolution ON "${flyway:defaultSchema}".wompi_checkouts
    FOR SELECT TO fixup_wompi_checkout_resolver USING (true);

CREATE FUNCTION "${flyway:defaultSchema}".app_wompi_checkout_opener(p_reference text)
RETURNS uuid
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET row_security = on
AS $$
BEGIN
    IF "${flyway:defaultSchema}".app_current_user_id() IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501',
            MESSAGE = 'Wompi checkout resolution is not available to user sessions';
    END IF;
    RETURN (SELECT c.created_by_user_id FROM "${flyway:defaultSchema}".wompi_checkouts c
            WHERE c.reference = p_reference);
END
$$;

-- Flyway aplica esta migración en una transacción: no hay ventana con EXECUTE para PUBLIC.
REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".app_wompi_checkout_opener(text) FROM PUBLIC;
ALTER FUNCTION "${flyway:defaultSchema}".app_wompi_checkout_opener(text)
    OWNER TO fixup_wompi_checkout_resolver;
GRANT EXECUTE ON FUNCTION "${flyway:defaultSchema}".app_wompi_checkout_opener(text) TO fixup_app;
