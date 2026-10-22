-- FR-UC-09: el cliente califica el trabajo terminado (1 a 5 y reseña). El promedio queda en el
-- perfil público del técnico. FR-UC-16: el técnico se presenta con biografía y experiencia.

ALTER TABLE fixer_profiles ADD COLUMN bio VARCHAR(600);
ALTER TABLE fixer_profiles ADD COLUMN years_experience INTEGER;
ALTER TABLE fixer_profiles ADD COLUMN rating_average NUMERIC(3,2);
ALTER TABLE fixer_profiles ADD COLUMN rating_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE fixer_profiles ADD CONSTRAINT ck_fixer_years CHECK (years_experience IS NULL OR years_experience BETWEEN 0 AND 70);
ALTER TABLE fixer_profiles ADD CONSTRAINT ck_fixer_rating CHECK (
    (rating_count = 0 AND rating_average IS NULL) OR (rating_count > 0 AND rating_average BETWEEN 1 AND 5)
);

CREATE TABLE service_ratings (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(id),
    request_id UUID NOT NULL REFERENCES repair_requests(id),
    fixer_user_id UUID NOT NULL REFERENCES users(id),
    rater_user_id UUID NOT NULL REFERENCES users(id),
    score INTEGER NOT NULL,
    punctuality INTEGER,
    quality INTEGER,
    cleanliness INTEGER,
    comment VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_service_rating_job UNIQUE (job_id),
    CONSTRAINT ck_service_rating_score CHECK (score BETWEEN 1 AND 5),
    CONSTRAINT ck_service_rating_aspects CHECK (
        (punctuality IS NULL OR punctuality BETWEEN 1 AND 5)
        AND (quality IS NULL OR quality BETWEEN 1 AND 5)
        AND (cleanliness IS NULL OR cleanliness BETWEEN 1 AND 5)
    ),
    CONSTRAINT ck_service_rating_parties CHECK (rater_user_id <> fixer_user_id)
);

CREATE INDEX ix_service_ratings_fixer ON service_ratings (fixer_user_id, created_at DESC);
