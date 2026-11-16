-- RNF-05 (integridad y registro), RNF-11 (autorizacion) y RNF-12 (aislamiento) aplicados a la
-- bitacora de transiciones: quien puede leerla, quien puede escribirla y que no se pueda alterar.
--
-- Lectura: solo PLATFORM_ADMIN. Es una consulta administrativa; ningun usuario de negocio lee la
-- bitacora, ni siquiera las transiciones de los recursos que si puede ver.
--
-- Alta: la fila solo puede declarar como autor al actor de la sesion (app.current_user_id), o ser
-- una transicion de sistema (changed_by_user_id IS NULL y changed_by_subject = 'SYSTEM') escrita por
-- un proceso sin actor. Antes esta politica era WITH CHECK (true): una peticion HTTP podia entonces
-- atribuir su propia transicion a otro usuario y fabricar entradas de sistema. Ahora:
--   * una sesion con actor solo inserta filas a su propio nombre (la primera rama; cuando
--     changed_by_user_id IS NULL la comparacion da NULL, nunca verdadero);
--   * una sesion con actor no puede firmar como 'SYSTEM' (la segunda rama exige que no haya actor);
--   * los jobs de sistema, Flyway y los fixtures de prueba, que no publican actor, siguen pudiendo
--     registrar la transicion.
--
-- Sin UPDATE ni DELETE: la bitacora es append-only. V10 concede SELECT, INSERT, UPDATE y DELETE a
-- fixup_app por defecto sobre toda tabla nueva (ALTER DEFAULT PRIVILEGES), asi que hay que revocar
-- explicitamente como hace V46__wompi_checkouts_rls.sql; no basta con no declarar politicas de
-- UPDATE/DELETE, porque esos privilegios ya venian concedidos.
ALTER TABLE status_change_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE status_change_log FORCE ROW LEVEL SECURITY;

CREATE POLICY status_change_log_select ON status_change_log FOR SELECT TO fixup_app
    USING (app_current_user_has_role('PLATFORM_ADMIN'));

CREATE POLICY status_change_log_insert ON status_change_log FOR INSERT TO fixup_app
    WITH CHECK (
        changed_by_user_id = app_current_user_id()
        OR (
            changed_by_user_id IS NULL
            AND changed_by_subject = 'SYSTEM'
            AND app_current_user_id() IS NULL
        )
    );

REVOKE ALL ON status_change_log FROM fixup_app;
GRANT SELECT, INSERT ON status_change_log TO fixup_app;
