-- FR-UC-06: OWNER and TENANT may discover all published properties. Existing owner access
-- to their own rows and PLATFORM_ADMIN access remain unchanged. No access is added for
-- FIXER or REAL_ESTATE_MANAGER.
DROP POLICY properties_select ON properties;

CREATE POLICY properties_select ON properties FOR SELECT
    USING (
        app_current_user_has_role('PLATFORM_ADMIN')
        OR owner_user_id = app_current_user_id()
        OR (
            status = 'PUBLISHED'
            AND (
                app_current_user_has_role('OWNER')
                OR app_current_user_has_role('TENANT')
            )
        )
    );

-- Reduce published-property geographic search candidates before Haversine.
CREATE INDEX idx_properties_published_geolocation
    ON properties (latitude, longitude, id)
    WHERE status = 'PUBLISHED' AND latitude IS NOT NULL AND longitude IS NOT NULL;
