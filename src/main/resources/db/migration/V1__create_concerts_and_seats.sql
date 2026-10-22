CREATE TABLE concerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL CHECK (length(trim(name)) > 0),
    starts_at TIMESTAMPTZ NOT NULL,
    sale_starts_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    crowdgate_gate_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT concerts_status_valid CHECK (
        status IN ('DRAFT', 'ON_SALE', 'SOLD_OUT', 'CANCELLED', 'FINISHED')
    ),
    CONSTRAINT concerts_sale_before_start CHECK (sale_starts_at <= starts_at)
);

CREATE TABLE seats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    concert_id UUID NOT NULL REFERENCES concerts(id),
    section VARCHAR(64) NOT NULL CHECK (length(trim(section)) > 0),
    seat_row VARCHAR(32) NOT NULL CHECK (length(trim(seat_row)) > 0),
    seat_number INTEGER NOT NULL CHECK (seat_number > 0),
    price NUMERIC(12, 2) NOT NULL CHECK (price > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    CONSTRAINT seats_status_valid CHECK (status IN ('AVAILABLE', 'RESERVED', 'SOLD')),
    CONSTRAINT seats_position_unique UNIQUE (concert_id, section, seat_row, seat_number)
);

CREATE INDEX seats_concert_status ON seats (concert_id, status, id);
