-- FR-UC-01: a remodeling groups ordinary repair requests without changing their cardinality.
CREATE TABLE remodeling_projects (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    title VARCHAR(150) NOT NULL,
    description VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_remodeling_project_property FOREIGN KEY (property_id)
        REFERENCES properties(id) ON DELETE RESTRICT,
    CONSTRAINT fk_remodeling_project_owner FOREIGN KEY (owner_user_id)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_remodeling_project_title CHECK (length(trim(title)) > 0),
    CONSTRAINT ck_remodeling_project_description CHECK (
        description IS NULL OR length(trim(description)) > 0
    )
);

CREATE INDEX ix_remodeling_projects_owner_created
    ON remodeling_projects (owner_user_id, created_at DESC);
CREATE INDEX ix_remodeling_projects_property ON remodeling_projects (property_id);

CREATE TABLE remodeling_requirements (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    request_id UUID NOT NULL,
    position INTEGER NOT NULL,
    CONSTRAINT fk_remodeling_requirement_project FOREIGN KEY (project_id)
        REFERENCES remodeling_projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_remodeling_requirement_request FOREIGN KEY (request_id)
        REFERENCES repair_requests(id) ON DELETE RESTRICT,
    CONSTRAINT uq_remodeling_requirement_request UNIQUE (request_id),
    CONSTRAINT uq_remodeling_requirement_position UNIQUE (project_id, position),
    CONSTRAINT ck_remodeling_requirement_position CHECK (position >= 0)
);

CREATE INDEX ix_remodeling_requirements_project ON remodeling_requirements (project_id);
CREATE INDEX ix_remodeling_requirements_request ON remodeling_requirements (request_id);
