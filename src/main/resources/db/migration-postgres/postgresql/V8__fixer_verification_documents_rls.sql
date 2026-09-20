-- FR-UC-23: los documentos de verificacion de identidad son tan sensibles como repair_requests/
-- quotations (FR-UC-25) -- solo el fixer dueño y PLATFORM_ADMIN pueden verlos. Reusa las mismas
-- funciones de sesion de V6 en vez de reinventar la lectura de app.current_user_id/roles.
--
-- fixup_app y el resto del modelo de confianza (RlsSessionTransactionManager activa la sesion,
-- FORCE RLS deja fuera incluso al dueño de la tabla) ya quedaron establecidos en V6; esta migracion
-- solo añade las politicas para la tabla nueva.

ALTER TABLE fixer_verification_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixer_verification_documents FORCE ROW LEVEL SECURITY;

CREATE POLICY fixer_verification_documents_select ON fixer_verification_documents FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR user_id = app_current_user_id()
    );

CREATE POLICY fixer_verification_documents_insert ON fixer_verification_documents FOR INSERT
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR user_id = app_current_user_id()
    );

CREATE POLICY fixer_verification_documents_update ON fixer_verification_documents FOR UPDATE
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR user_id = app_current_user_id()
    )
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR user_id = app_current_user_id()
    );
