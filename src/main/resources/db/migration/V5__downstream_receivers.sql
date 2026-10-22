CREATE TABLE webhook_receipts (
    consumer VARCHAR(16) NOT NULL CHECK (consumer IN ('TICKETS', 'ACCOUNTING', 'NOTIFICATIONS')),
    event_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL CHECK (length(trim(event_type)) > 0),
    payload_sha256 CHAR(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer, event_id)
);

CREATE TABLE ticket_records (
    ticket_number BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    reservation_id UUID NOT NULL UNIQUE,
    concert_id UUID NOT NULL,
    seat_id UUID NOT NULL,
    user_id UUID NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ticket_records_owner ON ticket_records (user_id, issued_at, ticket_number);

CREATE TABLE sales_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    payment_id UUID NOT NULL UNIQUE,
    reservation_id UUID NOT NULL UNIQUE,
    concert_id UUID NOT NULL,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX sales_entries_concert ON sales_entries (concert_id, recorded_at, id);

CREATE TABLE simulated_notifications (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(64) NOT NULL CHECK (length(trim(event_type)) > 0),
    user_id UUID,
    payload TEXT NOT NULL CHECK (jsonb_typeof(payload::jsonb) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX simulated_notifications_owner ON simulated_notifications (user_id, created_at, id);
