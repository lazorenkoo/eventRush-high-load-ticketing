CREATE TABLE notification_demo_failures (
    event_id UUID PRIMARY KEY,
    failed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
