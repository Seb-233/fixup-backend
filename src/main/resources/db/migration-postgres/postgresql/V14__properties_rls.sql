-- FR-UC-25: Row-Level Security policies for the properties table (introduced in V7).
-- Same trust model as repair_requests/quotations/fixer_verification_documents/chat_messages:
-- reuses app_current_user_id() and app_current_user_has_role() from V10__row_level_security.sql
-- instead of re-reading current_setting() directly.
--
-- An OWNER may only see and insert their own properties. PLATFORM_ADMIN has read access.
-- FIXER/TENANT/MANAGER are not granted access: no implemented use case requires it.
-- RLS acts as defence-in-depth; the application layer (PropertyAccess, CreateProperty,
-- ListOwnProperties, GetProperty) already enforces the same ownership rules.

ALTER TABLE properties ENABLE ROW LEVEL SECURITY;
ALTER TABLE properties FORCE ROW LEVEL SECURITY;

CREATE POLICY properties_select ON properties FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
    );

-- INSERT policy: only allow inserting a property with the caller's own user id as owner.
-- CreateProperty.execute() already copies CurrentActor.internalUserId() into the domain object,
-- so this policy should never trigger for legitimate traffic; it is a safety net against any
-- future code path that forgets to set owner_user_id correctly.
CREATE POLICY properties_insert ON properties FOR INSERT
    WITH CHECK (
        owner_user_id = app_current_user_id()
    );