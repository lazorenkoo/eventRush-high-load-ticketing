CREATE TABLE reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    seat_id UUID NOT NULL REFERENCES seats(id),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT reservations_status_valid CHECK (
        status IN ('ACTIVE', 'CONFIRMED', 'EXPIRED', 'CANCELLED')
    ),
    CONSTRAINT reservations_positive_ttl CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX reservations_one_owner_per_seat ON reservations (seat_id)
    WHERE status IN ('ACTIVE', 'CONFIRMED');

CREATE INDEX reservations_due_expiry ON reservations (expires_at, id)
    WHERE status = 'ACTIVE';

CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id UUID NOT NULL UNIQUE REFERENCES reservations(id),
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    simulation_mode VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    complete_after TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT payments_status_valid CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT payments_mode_valid CHECK (
        simulation_mode IN ('SUCCESS', 'FAILED', 'DELAYED_SUCCESS')
    )
);

CREATE INDEX payments_due_completion ON payments (complete_after, id)
    WHERE status = 'PENDING';
