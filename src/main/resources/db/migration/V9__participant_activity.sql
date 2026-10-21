ALTER TABLE purchase_participants ADD COLUMN queue_state VARCHAR(16);
ALTER TABLE purchase_participants ADD COLUMN queue_observed_at TIMESTAMPTZ;
ALTER TABLE purchase_participants ADD CONSTRAINT participant_queue_observation_valid CHECK (
    (queue_state IS NULL AND queue_observed_at IS NULL)
    OR (queue_state IS NOT NULL AND queue_state IN ('WAITING', 'ADMITTED', 'ACTIVE')
        AND queue_observed_at IS NOT NULL)
);

CREATE TABLE participant_activity (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    participant_id UUID NOT NULL REFERENCES purchase_participants(id),
    event_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (
        kind IN ('REGISTRATION', 'QUEUE', 'RESERVATION', 'PAYMENT', 'TICKET', 'ACCOUNTING', 'NOTIFICATION')
    ),
    state VARCHAR(32) NOT NULL CHECK (state ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (participant_id, event_id, kind)
);

CREATE INDEX participant_activity_cursor ON participant_activity (participant_id, id);
