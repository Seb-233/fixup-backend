-- FR-UC-01: only the owner sees a remodeling as a whole; PLATFORM_ADMIN has read-only visibility.
ALTER TABLE remodeling_projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE remodeling_projects FORCE ROW LEVEL SECURITY;

CREATE POLICY remodeling_projects_select ON remodeling_projects FOR SELECT
    USING (
        owner_user_id = app_current_user_id()
        OR app_current_user_has_role('PLATFORM_ADMIN')
    );

CREATE POLICY remodeling_projects_insert ON remodeling_projects FOR INSERT
    WITH CHECK (owner_user_id = app_current_user_id());

ALTER TABLE remodeling_requirements ENABLE ROW LEVEL SECURITY;
ALTER TABLE remodeling_requirements FORCE ROW LEVEL SECURITY;

CREATE POLICY remodeling_requirements_select ON remodeling_requirements FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM remodeling_projects p
            WHERE p.id = remodeling_requirements.project_id
              AND (
                  p.owner_user_id = app_current_user_id()
                  OR app_current_user_has_role('PLATFORM_ADMIN')
              )
        )
    );

CREATE POLICY remodeling_requirements_insert ON remodeling_requirements FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM remodeling_projects p
            WHERE p.id = remodeling_requirements.project_id
              AND p.owner_user_id = app_current_user_id()
        )
    );
