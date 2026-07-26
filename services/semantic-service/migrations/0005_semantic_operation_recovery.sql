CREATE UNIQUE INDEX IF NOT EXISTS ux_benchmark_runs_operation
    ON benchmark_runs (operation_id)
    WHERE operation_id IS NOT NULL;
