-- FR-UC-17: portafolio visual del técnico.
-- Owned by media. No JPA association exposes another module's entity.

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
