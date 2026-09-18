CREATE TABLE users (
    id UUID PRIMARY KEY,
    auth0_subject VARCHAR(255) NOT NULL,
    email VARCHAR(320),
    display_name VARCHAR(200),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_users_auth0_subject UNIQUE (auth0_subject),
    CONSTRAINT ck_users_subject CHECK (length(trim(auth0_subject)) > 0),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED'))
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT ck_user_roles_role CHECK (
        role IN ('OWNER', 'TENANT', 'FIXER', 'REAL_ESTATE_MANAGER', 'PLATFORM_ADMIN')
    )
);

-- Owned by fixers. No JPA association exposes another module's entity.
CREATE TABLE fixer_profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    verification_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_fixer_verification_status CHECK (
        verification_status IN ('PENDING', 'VERIFIED', 'REJECTED', 'SUSPENDED')
    )
);
