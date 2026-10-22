-- FR-UC-26: una remodelación pre-arriendo tiene un canon objetivo y termina con el inmueble
-- publicado en arriendo, cuando el propietario lo confirma.
ALTER TABLE remodeling_projects ADD COLUMN goal VARCHAR(16) NOT NULL DEFAULT 'IMPROVEMENT';
ALTER TABLE remodeling_projects ADD COLUMN target_rent BIGINT;
ALTER TABLE remodeling_projects ADD COLUMN target_ready_date DATE;
ALTER TABLE remodeling_projects ADD COLUMN listing_published_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE remodeling_projects ADD CONSTRAINT ck_remodeling_goal CHECK (goal IN ('IMPROVEMENT', 'PRE_RENTAL'));
ALTER TABLE remodeling_projects ADD CONSTRAINT ck_remodeling_target_rent CHECK (
    (goal = 'PRE_RENTAL' AND target_rent IS NOT NULL AND target_rent > 0)
    OR (goal = 'IMPROVEMENT' AND target_rent IS NULL)
);
