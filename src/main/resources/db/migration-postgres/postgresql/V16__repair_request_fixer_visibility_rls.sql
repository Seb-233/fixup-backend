-- V16__repair_request_fixer_visibility_rls.sql
-- FR-UC-04: Update repair_requests RLS to ensure only verified fixers with matching specialty see OPEN requests.

DROP POLICY IF EXISTS repair_requests_select ON repair_requests;
CREATE POLICY repair_requests_select ON repair_requests FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
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

DROP POLICY IF EXISTS repair_requests_update ON repair_requests;
-- The UPDATE USING clause is deliberately broader than the WITH CHECK clause.
-- A fixer eligible to view an OPEN request must be able to lock it via SELECT ... FOR UPDATE
-- when submitting a quotation. The WITH CHECK clause restricts the actual update.
CREATE POLICY repair_requests_update ON repair_requests FOR UPDATE
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
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
        OR assigned_fixer_user_id = app_current_user_id()
    );