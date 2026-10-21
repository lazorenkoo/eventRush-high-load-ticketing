ALTER TABLE purchase_participants ADD CONSTRAINT participant_run_seat_unique
    UNIQUE (id, run_id, concert_id, preferred_seat_id);

CREATE TABLE load_test_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    concert_id UUID NOT NULL REFERENCES concerts(id),
    status VARCHAR(16) NOT NULL DEFAULT 'READY'
        CHECK (status IN ('READY', 'RUNNING', 'STOPPING', 'COMPLETED', 'STOPPED', 'FAILED')),
    participant_count INTEGER NOT NULL CHECK (participant_count BETWEEN 1 AND 50000),
    concurrency INTEGER NOT NULL DEFAULT 20 CHECK (concurrency BETWEEN 1 AND 100),
    input_sha256 VARCHAR(64) NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    UNIQUE (id, concert_id),
    CONSTRAINT load_run_timestamps_valid CHECK (
        (status = 'READY' AND started_at IS NULL AND finished_at IS NULL)
        OR (status IN ('RUNNING', 'STOPPING') AND started_at IS NOT NULL AND finished_at IS NULL)
        OR (status IN ('COMPLETED', 'STOPPED', 'FAILED') AND finished_at IS NOT NULL)
    ),
    CHECK (started_at IS NULL OR started_at >= created_at),
    CHECK (finished_at IS NULL OR finished_at >= COALESCE(started_at, created_at))
);

CREATE TABLE load_test_clients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL,
    concert_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 50000),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    seat_id UUID NOT NULL,
    participant_id UUID UNIQUE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'SOLD_OUT', 'FAILED', 'CANCELLED')),
    finished_at TIMESTAMPTZ,
    error_code VARCHAR(64) CHECK (error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    UNIQUE (run_id, ordinal),
    FOREIGN KEY (run_id, concert_id) REFERENCES load_test_runs(id, concert_id),
    FOREIGN KEY (participant_id, run_id, concert_id, seat_id)
        REFERENCES purchase_participants(id, run_id, concert_id, preferred_seat_id),
    FOREIGN KEY (seat_id, concert_id) REFERENCES seats(id, concert_id),
    CHECK (first_name = btrim(first_name) AND length(first_name) > 0 AND first_name !~ '[[:cntrl:]]'),
    CHECK (last_name = btrim(last_name) AND length(last_name) > 0 AND last_name !~ '[[:cntrl:]]'),
    CHECK ((status IN ('PENDING', 'RUNNING') AND finished_at IS NULL)
        OR (status IN ('SUCCEEDED', 'SOLD_OUT', 'FAILED', 'CANCELLED') AND finished_at IS NOT NULL)),
    CHECK (error_code IS NULL OR status = 'FAILED')
);

CREATE INDEX load_runs_created ON load_test_runs (created_at DESC, id);
CREATE INDEX load_clients_dispatch ON load_test_clients (run_id, status, ordinal);