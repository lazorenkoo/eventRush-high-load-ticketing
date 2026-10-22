ALTER TABLE load_test_clients ADD COLUMN lease_token UUID;
ALTER TABLE load_test_clients ADD COLUMN lease_expires_at TIMESTAMPTZ;
ALTER TABLE load_test_clients ADD CONSTRAINT load_client_lease_valid CHECK (
    (status = 'RUNNING' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    OR (status <> 'RUNNING' AND lease_token IS NULL AND lease_expires_at IS NULL)
);
CREATE INDEX load_clients_expiring ON load_test_clients (run_id, lease_expires_at)
    WHERE status = 'RUNNING';
