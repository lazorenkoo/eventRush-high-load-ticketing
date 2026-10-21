ALTER TABLE seats ADD CONSTRAINT seats_id_concert_unique UNIQUE (id, concert_id);
ALTER TABLE reservations ADD CONSTRAINT reservations_identity_unique UNIQUE (id, user_id, seat_id);

CREATE TABLE purchase_participants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    concert_id UUID NOT NULL REFERENCES concerts(id),
    preferred_seat_id UUID NOT NULL,
    reservation_id UUID UNIQUE,
    FOREIGN KEY (preferred_seat_id, concert_id) REFERENCES seats(id, concert_id),
    FOREIGN KEY (reservation_id, user_id, preferred_seat_id) REFERENCES reservations(id, user_id, seat_id),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    source VARCHAR(8) NOT NULL DEFAULT 'MANUAL',
    run_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT participant_first_name_valid CHECK (
        first_name = btrim(first_name) AND length(first_name) > 0
        AND first_name !~ '[[:cntrl:]]'
    ),
    CONSTRAINT participant_last_name_valid CHECK (
        last_name = btrim(last_name) AND length(last_name) > 0
        AND last_name !~ '[[:cntrl:]]'
    ),
    CONSTRAINT participant_source_valid CHECK (
        (source = 'MANUAL' AND run_id IS NULL)
        OR (source = 'TEST' AND run_id IS NOT NULL)
    )
);

CREATE INDEX participants_concert_created ON purchase_participants (concert_id, created_at, id);
CREATE INDEX participants_run_created ON purchase_participants (run_id, created_at, id)
    WHERE run_id IS NOT NULL;
