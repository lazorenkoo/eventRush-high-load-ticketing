CREATE TABLE admission_sessions (
    token_hash CHAR(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    owner_id UUID NOT NULL,
    gate_id UUID NOT NULL REFERENCES concerts(crowdgate_gate_id),
    queue_session_id UUID NOT NULL,
    session_credential VARCHAR(512) NOT NULL CHECK (length(session_credential) > 0),
    admission_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() + INTERVAL '1 day',
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);

CREATE INDEX admission_sessions_expiry ON admission_sessions(expires_at);
