-- FR-UC-12 + FR-UC-25: política RLS de UPDATE para properties.
--
-- V14__properties_rls habilitó RLS sobre properties con FORCE y creó solo políticas de SELECT e
-- INSERT, porque en ese momento un inmueble se creaba y se leía, nunca se modificaba. Publicar,
-- retirar y volver a publicar son UPDATE sobre la fila, y en PostgreSQL una tabla con RLS activo
-- rechaza toda operación para la que no existe política: sin esto, POST /properties/{id}/publish
-- fallaría contra PostgreSQL real aunque pase contra H2, que no implementa RLS.
--
-- Mismo modelo que el resto: el dueño manda sobre sus propios inmuebles. PLATFORM_ADMIN conserva
-- solo lectura, igual que en V14: ningún caso de uso le permite publicar a nombre de otro.
-- USING filtra las filas que puede modificar; WITH CHECK impide que un UPDATE cambie el dueño.

CREATE POLICY properties_update ON properties FOR UPDATE
    USING (
        owner_user_id = app_current_user_id()
    )
    WITH CHECK (
        owner_user_id = app_current_user_id()
    );
