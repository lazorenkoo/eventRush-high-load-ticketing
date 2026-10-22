CREATE UNIQUE INDEX single_active_load_run ON load_test_runs ((true))
    WHERE status IN ('RUNNING', 'STOPPING');
