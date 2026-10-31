-- Accounts and refresh tokens (docs/ERD.md, ADR-0002).

CREATE TABLE app_user (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Stored lower-cased by the application, so uniqueness is case-insensitive.
    email                 text NOT NULL,
    password_hash         text NOT NULL,
    full_name             text NOT NULL,
    role                  text NOT NULL DEFAULT 'STUDENT',
    status                text NOT NULL DEFAULT 'ACTIVE',
    suspension_cleared_at timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    version               bigint NOT NULL DEFAULT 0,

    CONSTRAINT app_user_email_unique UNIQUE (email),
    CONSTRAINT app_user_email_lower CHECK (email = lower(email)),
    CONSTRAINT app_user_email_length CHECK (char_length(email) BETWEEN 3 AND 254),
    CONSTRAINT app_user_full_name_length CHECK (char_length(full_name) BETWEEN 1 AND 100),
    CONSTRAINT app_user_role_valid CHECK (role IN ('STUDENT', 'MANAGER', 'ADMIN')),
    CONSTRAINT app_user_status_valid CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX app_user_role ON app_user (role);

CREATE TABLE refresh_token (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- SHA-256 of the token, hex encoded. The token itself is never stored.
    token_hash     text NOT NULL,
    family_id      uuid NOT NULL,
    expires_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    replaced_by_id uuid REFERENCES refresh_token (id),
    created_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT refresh_token_hash_unique UNIQUE (token_hash),
    CONSTRAINT refresh_token_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX refresh_token_user ON refresh_token (user_id);
CREATE INDEX refresh_token_family ON refresh_token (family_id);
