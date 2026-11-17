CREATE TABLE status_change_log (
    id UUID PRIMARY KEY,
    resource_type VARCHAR(32) NOT NULL,
    resource_id UUID NOT NULL,
    previous_status VARCHAR(64),
    new_status VARCHAR(64) NOT NULL,
    changed_by_user_id UUID,
    changed_by_subject VARCHAR(191) NOT NULL,
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_status_change_log_resource
    ON status_change_log(resource_type, resource_id, changed_at DESC);

CREATE INDEX idx_status_change_log_changed_at
    ON status_change_log(changed_at DESC);
