-- FR-UC-25: aislamiento por fila para las tablas y relaciones nuevas de la entrega completa.
-- Mismo modelo de confianza que V10: app_current_user_id() y app_current_user_has_role() leen el
-- actor que RlsSessionTransactionManager publica en cada transacción con usuario. Las tablas sin
-- actor (Flyway, programadores de tareas) siguen la conexión original.

-- ============== properties (FR-UC-02, FR-UC-06, FR-UC-08, FR-UC-11) ==============
-- Además de lo que ya veían el dueño, el admin y quien busca en el catálogo, ven el inmueble el
-- administrador inmobiliario que lo gestiona y el arrendatario con contrato vigente.
DROP POLICY properties_select ON properties;
CREATE POLICY properties_select ON properties FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR manager_user_id = app_current_user_id()
        OR (
            status = 'PUBLISHED'
            AND (
                app_current_user_has_role('OWNER')
                OR app_current_user_has_role('TENANT')
                OR app_current_user_has_role('REAL_ESTATE_MANAGER')
            )
        )
        OR EXISTS (
            SELECT 1 FROM rental_contracts c
            WHERE c.property_id = properties.id
              AND c.tenant_user_id = app_current_user_id()
              AND c.status IN ('ACTIVE', 'RENEWED')
        )
    );

-- Editar, publicar o retirar el aviso: dueño, administrador del inmueble o admin de plataforma.
CREATE POLICY properties_update ON properties FOR UPDATE
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR manager_user_id = app_current_user_id()
    )
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR manager_user_id = app_current_user_id()
    );

-- La galería sigue la visibilidad del inmueble; la modifica quien controla el inmueble.
ALTER TABLE property_photos ENABLE ROW LEVEL SECURITY;
ALTER TABLE property_photos FORCE ROW LEVEL SECURITY;

CREATE POLICY property_photos_select ON property_photos FOR SELECT
    USING (EXISTS (SELECT 1 FROM properties p WHERE p.id = property_photos.property_id));

CREATE POLICY property_photos_write ON property_photos FOR ALL
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR EXISTS (
            SELECT 1 FROM properties p
            WHERE p.id = property_photos.property_id
              AND (p.owner_user_id = app_current_user_id() OR p.manager_user_id = app_current_user_id())
        )
    )
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR EXISTS (
            SELECT 1 FROM properties p
            WHERE p.id = property_photos.property_id
              AND (p.owner_user_id = app_current_user_id() OR p.manager_user_id = app_current_user_id())
        )
    );

-- FR-UC-07: el historial lo lee quien puede ver el inmueble. Lo escribe el técnico del trabajo al
-- cerrarlo y la calificación la copia quien calificó ese trabajo.
ALTER TABLE property_work_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE property_work_history FORCE ROW LEVEL SECURITY;

CREATE POLICY property_work_history_select ON property_work_history FOR SELECT
    USING (EXISTS (SELECT 1 FROM properties p WHERE p.id = property_work_history.property_id));

CREATE POLICY property_work_history_insert ON property_work_history FOR INSERT
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR EXISTS (
            SELECT 1 FROM jobs j
            WHERE j.id = property_work_history.job_id AND j.fixer_user_id = app_current_user_id()
        )
    );

CREATE POLICY property_work_history_update ON property_work_history FOR UPDATE
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR EXISTS (
            SELECT 1 FROM service_ratings r
            WHERE r.job_id = property_work_history.job_id AND r.rater_user_id = app_current_user_id()
        )
    )
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR EXISTS (
            SELECT 1 FROM service_ratings r
            WHERE r.job_id = property_work_history.job_id AND r.rater_user_id = app_current_user_id()
        )
    );

-- ============== repair_requests (FR-UC-08, FR-UC-11) ==============
DROP POLICY repair_requests_select ON repair_requests;
CREATE POLICY repair_requests_select ON repair_requests FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR requested_by_user_id = app_current_user_id()
        OR manager_user_id = app_current_user_id()
        OR assigned_fixer_user_id = app_current_user_id()
        OR (
            status = 'OPEN'
            AND app_current_user_has_role('FIXER')
            AND EXISTS (
                SELECT 1
                FROM users u
                JOIN fixer_profiles fp ON fp.user_id = u.id
                JOIN fixer_specialties fs ON fs.fixer_user_id = u.id
                WHERE u.id = app_current_user_id()
                  AND u.status = 'ACTIVE'
                  AND fp.verification_status = 'VERIFIED'
                  AND fs.specialty = repair_requests.specialty
            )
        )
    );

-- Abre la solicitud quien la reporta (dueño, arrendatario o administrador); la relación con el
-- inmueble la comprueba CreateRepairRequest antes de insertar.
DROP POLICY repair_requests_insert ON repair_requests;
CREATE POLICY repair_requests_insert ON repair_requests FOR INSERT
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR requested_by_user_id = app_current_user_id()
    );

DROP POLICY repair_requests_update ON repair_requests;
CREATE POLICY repair_requests_update ON repair_requests FOR UPDATE
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR requested_by_user_id = app_current_user_id()
        OR manager_user_id = app_current_user_id()
        OR assigned_fixer_user_id = app_current_user_id()
        OR (
            status = 'OPEN'
            AND app_current_user_has_role('FIXER')
            AND EXISTS (
                SELECT 1
                FROM users u
                JOIN fixer_profiles fp ON fp.user_id = u.id
                JOIN fixer_specialties fs ON fs.fixer_user_id = u.id
                WHERE u.id = app_current_user_id()
                  AND u.status = 'ACTIVE'
                  AND fp.verification_status = 'VERIFIED'
                  AND fs.specialty = repair_requests.specialty
            )
        )
    )
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR requested_by_user_id = app_current_user_id()
        OR manager_user_id = app_current_user_id()
        OR assigned_fixer_user_id = app_current_user_id()
    );

-- ============== quotations: el administrador del inmueble también compara y decide ==============
DROP POLICY quotations_select ON quotations;
CREATE POLICY quotations_select ON quotations FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR fixer_user_id = app_current_user_id()
        OR EXISTS (
            SELECT 1 FROM repair_requests r
            WHERE r.id = quotations.request_id
              AND (r.owner_user_id = app_current_user_id() OR r.manager_user_id = app_current_user_id())
        )
    );

DROP POLICY quotations_update ON quotations;
CREATE POLICY quotations_update ON quotations FOR UPDATE
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR fixer_user_id = app_current_user_id()
        OR EXISTS (
            SELECT 1 FROM repair_requests r
            WHERE r.id = quotations.request_id
              AND (r.owner_user_id = app_current_user_id() OR r.manager_user_id = app_current_user_id())
        )
    )
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR fixer_user_id = app_current_user_id()
        OR EXISTS (
            SELECT 1 FROM repair_requests r
            WHERE r.id = quotations.request_id
              AND (r.owner_user_id = app_current_user_id() OR r.manager_user_id = app_current_user_id())
        )
    );

-- ============== chat_messages (FR-UC-24) ==============
-- Participan el dueño, quien reportó, el administrador del inmueble y el técnico asignado. Se
-- escribe mientras haya técnico asignado y la solicitud no esté cerrada; leer nunca se restringe
-- por estado.
DROP POLICY chat_messages_select ON chat_messages;
CREATE POLICY chat_messages_select ON chat_messages FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR EXISTS (
            SELECT 1 FROM repair_requests r
            WHERE r.id = chat_messages.request_id
              AND (r.owner_user_id = app_current_user_id()
                   OR r.requested_by_user_id = app_current_user_id()
                   OR r.manager_user_id = app_current_user_id()
                   OR r.assigned_fixer_user_id = app_current_user_id())
        )
    );

DROP POLICY chat_messages_insert ON chat_messages;
CREATE POLICY chat_messages_insert ON chat_messages FOR INSERT
    WITH CHECK (
        sender_user_id = app_current_user_id()
        AND EXISTS (
            SELECT 1 FROM repair_requests r
            WHERE r.id = chat_messages.request_id
              AND r.assigned_fixer_user_id IS NOT NULL
              AND r.status NOT IN ('OPEN', 'COMPLETED', 'CANCELLED')
              AND (r.owner_user_id = app_current_user_id()
                   OR r.requested_by_user_id = app_current_user_id()
                   OR r.manager_user_id = app_current_user_id()
                   OR r.assigned_fixer_user_id = app_current_user_id())
        )
    );

-- ============== request_invitations (FR-UC-11) ==============
ALTER TABLE request_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE request_invitations FORCE ROW LEVEL SECURITY;

CREATE POLICY request_invitations_select ON request_invitations FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR fixer_user_id = app_current_user_id()
        OR invited_by_user_id = app_current_user_id()
        OR EXISTS (
            SELECT 1 FROM repair_requests r
            WHERE r.id = request_invitations.request_id
              AND (r.owner_user_id = app_current_user_id() OR r.manager_user_id = app_current_user_id())
        )
    );

CREATE POLICY request_invitations_insert ON request_invitations FOR INSERT
    WITH CHECK (invited_by_user_id = app_current_user_id());

-- ============== technical_visits (FR-UC-03, FR-UC-27) ==============
ALTER TABLE technical_visits ENABLE ROW LEVEL SECURITY;
ALTER TABLE technical_visits FORCE ROW LEVEL SECURITY;

CREATE POLICY technical_visits_select ON technical_visits FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR requested_by_user_id = app_current_user_id()
        OR manager_user_id = app_current_user_id()
        OR fixer_user_id = app_current_user_id()
    );

CREATE POLICY technical_visits_insert ON technical_visits FOR INSERT
    WITH CHECK (
        proposed_by_user_id = app_current_user_id()
        AND (owner_user_id = app_current_user_id()
             OR requested_by_user_id = app_current_user_id()
             OR manager_user_id = app_current_user_id()
             OR fixer_user_id = app_current_user_id())
    );

CREATE POLICY technical_visits_update ON technical_visits FOR UPDATE
    USING (
        owner_user_id = app_current_user_id()
        OR requested_by_user_id = app_current_user_id()
        OR manager_user_id = app_current_user_id()
        OR fixer_user_id = app_current_user_id()
    )
    WITH CHECK (
        owner_user_id = app_current_user_id()
        OR requested_by_user_id = app_current_user_id()
        OR manager_user_id = app_current_user_id()
        OR fixer_user_id = app_current_user_id()
    );

ALTER TABLE visit_checklist_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE visit_checklist_items FORCE ROW LEVEL SECURITY;

CREATE POLICY visit_checklist_items_select ON visit_checklist_items FOR SELECT
    USING (EXISTS (SELECT 1 FROM technical_visits v WHERE v.id = visit_checklist_items.visit_id));

CREATE POLICY visit_checklist_items_write ON visit_checklist_items FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM technical_visits v
            WHERE v.id = visit_checklist_items.visit_id
              AND (v.owner_user_id = app_current_user_id()
                   OR v.requested_by_user_id = app_current_user_id()
                   OR v.manager_user_id = app_current_user_id()
                   OR v.fixer_user_id = app_current_user_id())
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM technical_visits v
            WHERE v.id = visit_checklist_items.visit_id
              AND (v.owner_user_id = app_current_user_id()
                   OR v.requested_by_user_id = app_current_user_id()
                   OR v.manager_user_id = app_current_user_id()
                   OR v.fixer_user_id = app_current_user_id())
        )
    );

-- ============== owner_payments (FR-UC-05, FR-UC-22) ==============
-- Ven el cobro quien lo paga (dueño o administrador), el técnico que lo recibe y el admin. Nace
-- en la transacción de quien acepta la cotización y lo modifica quien lo paga.
ALTER TABLE owner_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE owner_payments FORCE ROW LEVEL SECURITY;

CREATE POLICY owner_payments_select ON owner_payments FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR manager_user_id = app_current_user_id()
        OR fixer_user_id = app_current_user_id()
    );

CREATE POLICY owner_payments_insert ON owner_payments FOR INSERT
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR manager_user_id = app_current_user_id()
    );

CREATE POLICY owner_payments_update ON owner_payments FOR UPDATE
    USING (owner_user_id = app_current_user_id() OR manager_user_id = app_current_user_id())
    WITH CHECK (owner_user_id = app_current_user_id() OR manager_user_id = app_current_user_id());

-- ============== service_ratings (FR-UC-09) ==============
-- Las reseñas son públicas para cualquier usuario autenticado; solo las escribe quien califica.
ALTER TABLE service_ratings ENABLE ROW LEVEL SECURITY;
ALTER TABLE service_ratings FORCE ROW LEVEL SECURITY;

CREATE POLICY service_ratings_select ON service_ratings FOR SELECT
    USING (app_current_user_id() IS NOT NULL);

CREATE POLICY service_ratings_insert ON service_ratings FOR INSERT
    WITH CHECK (rater_user_id = app_current_user_id());

-- ============== remodeling_projects (FR-UC-26) ==============
CREATE POLICY remodeling_projects_update ON remodeling_projects FOR UPDATE
    USING (owner_user_id = app_current_user_id())
    WITH CHECK (owner_user_id = app_current_user_id());
