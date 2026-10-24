-- FR-UC-19: estados intermedios del trabajo, evidencias fotográficas y bitácora de cambios.
ALTER TABLE jobs DROP CONSTRAINT ck_job_status;
ALTER TABLE jobs ADD CONSTRAINT ck_job_status CHECK (status IN ('ASSIGNED', 'SCHEDULED', 'IN_PROGRESS', 'COMPLETED'));
ALTER TABLE jobs DROP CONSTRAINT ck_job_completion;
ALTER TABLE jobs ADD CONSTRAINT ck_job_completion CHECK (
    (status <> 'COMPLETED' AND completed_at IS NULL)
    OR (status = 'COMPLETED' AND completed_at IS NOT NULL)
);
ALTER TABLE jobs ADD COLUMN scheduled_for TIMESTAMP WITH TIME ZONE;
ALTER TABLE jobs ADD COLUMN started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE jobs ADD COLUMN progress_note VARCHAR(1000);

CREATE INDEX ix_jobs_request ON jobs (request_id);
CREATE INDEX ix_jobs_owner ON jobs (owner_user_id, created_at DESC);

CREATE TABLE job_evidences (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    media_id UUID NOT NULL REFERENCES media_assets(id),
    stage VARCHAR(16) NOT NULL,
    caption VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_job_evidence_media UNIQUE (media_id),
    CONSTRAINT ck_job_evidence_stage CHECK (stage IN ('BEFORE', 'DURING', 'AFTER'))
);
CREATE INDEX ix_job_evidences_job ON job_evidences (job_id, created_at);

CREATE TABLE job_status_log (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL,
    note VARCHAR(1000),
    changed_by_user_id UUID NOT NULL REFERENCES users(id),
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_job_log_status CHECK (status IN ('ASSIGNED', 'SCHEDULED', 'IN_PROGRESS', 'COMPLETED'))
);
CREATE INDEX ix_job_status_log_job ON job_status_log (job_id, changed_at);
