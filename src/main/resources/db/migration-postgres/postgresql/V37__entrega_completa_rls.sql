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
        (app_current_user_has_role('PLATFORM_ADMIN')
        OR EXISTS (
            SELECT 1 FROM properties p
            WHERE p.id = property_photos.property_id
              AND (p.owner_user_id = app_current_user_id() OR p.manager_user_id = app_current_user_id())
        ))
        -- Owners and managers share the property's gallery. Reordering an existing photo
        -- must work even when the other authorized party uploaded it.
        AND EXISTS (
            SELECT 1 FROM media_assets m
            JOIN properties p ON p.id = property_photos.property_id
            WHERE m.id = property_photos.media_id
              AND m.purpose = 'PROPERTY_PHOTO' AND m.status = 'ATTACHED'
              AND (m.owner_user_id = app_current_user_id()
                   OR m.owner_user_id = p.owner_user_id
                   OR m.owner_user_id = p.manager_user_id)
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
            SELECT 1 FROM jobs j JOIN repair_requests r ON r.id = j.request_id
            WHERE j.id = property_work_history.job_id AND j.fixer_user_id = app_current_user_id()
              AND j.status = 'COMPLETED'
              AND j.request_id = property_work_history.request_id
              AND j.fixer_user_id = property_work_history.fixer_user_id
              AND r.property_id = property_work_history.property_id
              AND r.specialty = property_work_history.specialty
              AND j.completed_at = property_work_history.completed_at
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

-- RLS authorizes the row, but cannot compare OLD and NEW. The existing write path only
-- changes rating; the identity and completed-work snapshot are immutable for every actor.
CREATE FUNCTION public.preserve_property_work_history_identity() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
    SET search_path = pg_catalog
    AS $$
BEGIN
    IF (to_jsonb(NEW) - 'rating') IS DISTINCT FROM (to_jsonb(OLD) - 'rating') THEN
        RAISE EXCEPTION 'Completed-work identity and snapshot are immutable'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION public.preserve_property_work_history_identity() FROM PUBLIC;

CREATE TRIGGER preserve_property_work_history_identity
    BEFORE UPDATE ON public.property_work_history
    FOR EACH ROW EXECUTE FUNCTION public.preserve_property_work_history_identity();

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

-- Preserve historical owner/admin insertion. The new reporter path must prove the property
-- relationship rather than grant access just because requested_by equals the session actor.
DROP POLICY repair_requests_insert ON repair_requests;
CREATE POLICY repair_requests_insert ON repair_requests FOR INSERT
    WITH CHECK (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR (requested_by_user_id = app_current_user_id() AND EXISTS (
            SELECT 1 FROM properties p
            WHERE p.id = repair_requests.property_id
              AND p.owner_user_id = repair_requests.owner_user_id
              AND p.manager_user_id IS NOT DISTINCT FROM repair_requests.manager_user_id
              AND (
                  (app_current_user_has_role('REAL_ESTATE_MANAGER') AND p.manager_user_id = app_current_user_id())
                  OR (app_current_user_has_role('TENANT') AND EXISTS (
                      SELECT 1 FROM rental_contracts c
                      WHERE c.property_id = p.id AND c.tenant_user_id = app_current_user_id()
                        AND c.status IN ('ACTIVE', 'RENEWED')
                        AND (CURRENT_TIMESTAMP AT TIME ZONE 'America/Bogota')::date BETWEEN c.start_date AND c.end_date
                  ))
              )
        ))
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
-- escribe mientras haya tecnico asignado y la solicitud siga activa. En COMPLETED se conserva
-- exactamente la escritura de propietario/tecnico admitida por V29 de develop; no se extiende
-- ese permiso a otros participantes sin una decision funcional. La lectura no depende del estado.
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
              AND (
                  (r.status IN ('ASSIGNED', 'SLA_WARNING', 'SLA_BREACHED')
                   AND (r.owner_user_id = app_current_user_id()
                        OR r.requested_by_user_id = app_current_user_id()
                        OR r.manager_user_id = app_current_user_id()
                        OR r.assigned_fixer_user_id = app_current_user_id()))
                  OR (r.status = 'COMPLETED'
                      AND (r.owner_user_id = app_current_user_id()
                           OR r.assigned_fixer_user_id = app_current_user_id()))
              )
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
    WITH CHECK (
        invited_by_user_id = app_current_user_id()
        AND EXISTS (
            SELECT 1 FROM repair_requests r
            JOIN fixer_profiles fp ON fp.user_id = request_invitations.fixer_user_id
            JOIN users u ON u.id = fp.user_id
            JOIN fixer_specialties fs ON fs.fixer_user_id = fp.user_id AND fs.specialty = r.specialty
            WHERE r.id = request_invitations.request_id AND r.status = 'OPEN'
              AND fp.verification_status = 'VERIFIED' AND u.status = 'ACTIVE'
              AND (app_current_user_has_role('PLATFORM_ADMIN')
                   OR r.owner_user_id = app_current_user_id()
                   OR r.manager_user_id = app_current_user_id())
        )
    );

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
        AND EXISTS (
            SELECT 1 FROM repair_requests r
            WHERE r.id = technical_visits.request_id
              AND r.status NOT IN ('COMPLETED', 'CANCELLED')
              AND r.property_id IS NOT DISTINCT FROM technical_visits.property_id
              AND r.owner_user_id = technical_visits.owner_user_id
              AND r.requested_by_user_id IS NOT DISTINCT FROM technical_visits.requested_by_user_id
              AND r.manager_user_id IS NOT DISTINCT FROM technical_visits.manager_user_id
              AND (r.assigned_fixer_user_id = technical_visits.fixer_user_id
                   OR (r.status = 'OPEN' AND technical_visits.kind <> 'EXECUTION' AND EXISTS (
                       SELECT 1 FROM quotations q
                       WHERE q.request_id = r.id AND q.fixer_user_id = technical_visits.fixer_user_id
                   )))
        )
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

-- A visit keeps the request/property and participants captured when it was proposed.
-- Rescheduling may change proposed_by, dates, duration, status and outcome, not its parties.
CREATE FUNCTION public.preserve_technical_visit_parties() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER
    SET search_path = pg_catalog
    AS $$
BEGIN
    IF ROW(NEW.id, NEW.request_id, NEW.property_id, NEW.owner_user_id,
           NEW.requested_by_user_id, NEW.manager_user_id, NEW.fixer_user_id)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.request_id, OLD.property_id, OLD.owner_user_id,
           OLD.requested_by_user_id, OLD.manager_user_id, OLD.fixer_user_id) THEN
        RAISE EXCEPTION 'Visit identity and participants are immutable'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION public.preserve_technical_visit_parties() FROM PUBLIC;

CREATE TRIGGER preserve_technical_visit_parties
    BEFORE UPDATE ON public.technical_visits
    FOR EACH ROW EXECUTE FUNCTION public.preserve_technical_visit_parties();

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

-- ============== service_ratings (FR-UC-09) ==============
-- Las reseñas son públicas para cualquier usuario autenticado; solo las escribe quien califica.
ALTER TABLE service_ratings ENABLE ROW LEVEL SECURITY;
ALTER TABLE service_ratings FORCE ROW LEVEL SECURITY;

CREATE POLICY service_ratings_select ON service_ratings FOR SELECT
    USING (app_current_user_id() IS NOT NULL);

CREATE POLICY service_ratings_insert ON service_ratings FOR INSERT
    WITH CHECK (
        rater_user_id = app_current_user_id()
        AND EXISTS (
            SELECT 1 FROM jobs j JOIN repair_requests r ON r.id = j.request_id
            WHERE j.id = service_ratings.job_id AND j.status = 'COMPLETED'
              AND j.request_id = service_ratings.request_id
              AND j.fixer_user_id = service_ratings.fixer_user_id
              AND (r.owner_user_id = app_current_user_id()
                   OR r.requested_by_user_id = app_current_user_id()
                   OR r.manager_user_id = app_current_user_id())
        )
    );

-- Evidence and log are append-only through the existing use cases. No UPDATE/DELETE policy
-- is granted. Identifiers are checked against persisted jobs/requests/media, not trusted alone.
ALTER TABLE job_evidences ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_evidences FORCE ROW LEVEL SECURITY;

CREATE POLICY job_evidences_select ON job_evidences FOR SELECT USING (
    app_current_user_has_role('PLATFORM_ADMIN')
    OR EXISTS (
        SELECT 1 FROM jobs j JOIN repair_requests r ON r.id = j.request_id
        WHERE j.id = job_evidences.job_id
          AND (j.owner_user_id = app_current_user_id() OR j.fixer_user_id = app_current_user_id()
               OR r.requested_by_user_id = app_current_user_id() OR r.manager_user_id = app_current_user_id())
    )
);

CREATE POLICY job_evidences_insert ON job_evidences FOR INSERT WITH CHECK (
    app_current_user_has_role('FIXER')
    AND EXISTS (
        SELECT 1 FROM jobs j
        JOIN users u ON u.id = j.fixer_user_id
        JOIN fixer_profiles fp ON fp.user_id = u.id
        JOIN media_assets m ON m.id = job_evidences.media_id
        WHERE j.id = job_evidences.job_id AND j.fixer_user_id = app_current_user_id()
          AND u.status = 'ACTIVE' AND fp.verification_status = 'VERIFIED'
          AND m.owner_user_id = app_current_user_id()
          AND m.purpose = 'JOB_EVIDENCE' AND m.status = 'ATTACHED'
    )
);

ALTER TABLE job_status_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_status_log FORCE ROW LEVEL SECURITY;

CREATE POLICY job_status_log_select ON job_status_log FOR SELECT USING (
    app_current_user_has_role('PLATFORM_ADMIN')
    OR EXISTS (
        SELECT 1 FROM jobs j JOIN repair_requests r ON r.id = j.request_id
        WHERE j.id = job_status_log.job_id
          AND (j.owner_user_id = app_current_user_id() OR j.fixer_user_id = app_current_user_id()
               OR r.requested_by_user_id = app_current_user_id() OR r.manager_user_id = app_current_user_id())
    )
);

CREATE POLICY job_status_log_insert ON job_status_log FOR INSERT WITH CHECK (
    EXISTS (
        SELECT 1 FROM jobs j JOIN repair_requests r ON r.id = j.request_id
        JOIN users actor ON actor.id = app_current_user_id() AND actor.status = 'ACTIVE'
        WHERE j.id = job_status_log.job_id AND j.status = job_status_log.status
          AND (
              (job_status_log.status = 'ASSIGNED'
               AND (j.owner_user_id = actor.id OR r.manager_user_id = actor.id)
               -- The current acceptance listener attributes this initial record to the owner.
               AND job_status_log.changed_by_user_id = j.owner_user_id)
              OR (job_status_log.status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED')
                  AND j.fixer_user_id = actor.id AND job_status_log.changed_by_user_id = actor.id
                  AND app_current_user_has_role('FIXER')
                  AND EXISTS (SELECT 1 FROM fixer_profiles fp
                              WHERE fp.user_id = actor.id AND fp.verification_status = 'VERIFIED'))
          )
    )
);

-- ============== remodeling_projects (FR-UC-26) ==============
CREATE POLICY remodeling_projects_update ON remodeling_projects FOR UPDATE
    USING (owner_user_id = app_current_user_id())
    WITH CHECK (owner_user_id = app_current_user_id());
