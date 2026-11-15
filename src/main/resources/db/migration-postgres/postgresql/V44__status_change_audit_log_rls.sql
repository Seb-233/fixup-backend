ALTER TABLE status_change_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE status_change_log FORCE ROW LEVEL SECURITY;

-- Solo el administrador de la plataforma consulta la bitacora; ninguna politica habilita UPDATE ni
-- DELETE, por lo que la aplicacion no puede editar ni borrar entradas ya registradas.
CREATE POLICY status_change_log_select ON status_change_log FOR SELECT TO fixup_app
    USING (app_current_user_has_role('PLATFORM_ADMIN'));
CREATE POLICY status_change_log_insert ON status_change_log FOR INSERT TO fixup_app
    WITH CHECK (true);

GRANT SELECT, INSERT ON status_change_log TO fixup_app;
