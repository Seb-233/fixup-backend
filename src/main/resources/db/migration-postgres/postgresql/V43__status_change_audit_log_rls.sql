CREATE TABLE IF NOT EXISTS status_change_log (
    id uuid PRIMARY KEY,
    resource_type varchar(32) NOT NULL,
    resource_id uuid NOT NULL,
    previous_status varchar(64),
    new_status varchar(64) NOT NULL,
    changed_by_user_id uuid REFERENCES users(id),
    changed_by_subject varchar(191) NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_status_change_log_resource
    ON status_change_log(resource_type, resource_id, changed_at DESC);

CREATE INDEX IF NOT EXISTS idx_status_change_log_changed_at
    ON status_change_log(changed_at DESC);

ALTER TABLE status_change_log ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS status_change_log_select_policy ON status_change_log;
CREATE POLICY status_change_log_select_policy ON status_change_log
    FOR SELECT
    USING (app_current_user_has_role('PLATFORM_ADMIN'));

DROP POLICY IF EXISTS status_change_log_insert_policy ON status_change_log;
CREATE POLICY status_change_log_insert_policy ON status_change_log
    FOR INSERT
    WITH CHECK (true);
