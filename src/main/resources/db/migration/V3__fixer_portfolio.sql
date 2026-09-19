-- FR-UC-17: portafolio visual del técnico y ciclo de vida de medios.
-- Owned by media. No JPA association exposes another module's entity.

CREATE TABLE media_assets (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    purpose VARCHAR(50) NOT NULL,
    object_key VARCHAR(512) NOT NULL UNIQUE,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    upload_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_media_owner
        FOREIGN KEY (owner_user_id) REFERENCES users(id),
    CONSTRAINT ck_media_size
        CHECK (size_bytes > 0),
    CONSTRAINT ck_media_status
        CHECK (status IN ('PENDING', 'READY', 'ATTACHED', 'EXPIRED', 'INVALID', 'DELETION_PENDING', 'DELETED')),
    CONSTRAINT ck_media_purpose
        CHECK (purpose IN ('FIXER_PORTFOLIO'))
);

CREATE INDEX ix_media_assets_owner ON media_assets (owner_user_id, status);

CREATE TABLE media_deletion_jobs (
    id UUID PRIMARY KEY,
    media_asset_id UUID NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_deletion_job_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX ix_media_deletion_jobs_status ON media_deletion_jobs (status);

CREATE TABLE portfolio_pieces (
    id UUID PRIMARY KEY,
    fixer_user_id UUID NOT NULL,
    kind VARCHAR(32) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    title VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    display_position INTEGER NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_portfolio_kind CHECK (kind IN ('PHOTO', 'VIDEO')),
    CONSTRAINT ck_portfolio_visibility CHECK (visibility IN ('PUBLIC', 'HIDDEN')),
    CONSTRAINT ck_portfolio_storage_key CHECK (length(trim(storage_key)) > 0),
    CONSTRAINT ck_portfolio_title CHECK (length(trim(title)) > 0),
    CONSTRAINT ck_portfolio_position CHECK (display_position >= 1),
    CONSTRAINT uk_portfolio_piece_position UNIQUE (fixer_user_id, display_position)
);

-- media owns this table and does not join against another module's tables, so the fixer
-- reference is not a foreign key: it is resolved through the fixers api contract.
CREATE INDEX ix_portfolio_pieces_fixer ON portfolio_pieces (fixer_user_id, display_position);
