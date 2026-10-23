CREATE INDEX outgoing_events_concert_history
    ON outgoing_events ((payload::jsonb->>'concertId'), created_at DESC, id DESC);
