ALTER TABLE purchase_participants ADD COLUMN queue_key VARCHAR(64);
ALTER TABLE purchase_participants ADD CONSTRAINT participant_queue_key_valid
    CHECK (queue_key IS NULL OR queue_key ~ '^[0-9a-f]{64}$');
CREATE UNIQUE INDEX participants_queue_key ON purchase_participants (queue_key)
    WHERE queue_key IS NOT NULL;
