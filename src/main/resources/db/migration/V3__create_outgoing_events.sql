CREATE TABLE outgoing_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL CHECK (length(trim(event_type)) > 0),
    payload TEXT NOT NULL CHECK (jsonb_typeof(payload::jsonb) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error TEXT
);

CREATE INDEX outgoing_events_pending ON outgoing_events (next_attempt_at, created_at, id)
    WHERE published_at IS NULL;
