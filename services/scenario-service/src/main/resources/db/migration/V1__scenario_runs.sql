-- docs/db-ownership.md §3 "Scenario Service — scenario_service" (frozen by Phase 0).
CREATE TABLE scenario_runs (
    id              text PRIMARY KEY,
    scenario_name   text NOT NULL,
    status          text NOT NULL,
    correlation_id  uuid NOT NULL,
    order_id        text NULL,
    started_at      timestamptz NOT NULL,
    completed_at    timestamptz NULL,
    error_message   text NULL
);

CREATE INDEX idx_scenario_runs_scenario_name ON scenario_runs (scenario_name, started_at DESC);
CREATE INDEX idx_scenario_runs_status ON scenario_runs (status);
CREATE INDEX idx_scenario_runs_correlation_id ON scenario_runs (correlation_id);

CREATE TABLE scenario_run_timeline (
    id              bigserial PRIMARY KEY,
    run_id          text NOT NULL REFERENCES scenario_runs(id),
    sequence        integer NOT NULL,
    label           text NOT NULL,
    kind            text NOT NULL,
    occurred_at     timestamptz NOT NULL,
    detail          jsonb NULL,
    UNIQUE (run_id, sequence)
);

CREATE INDEX idx_scenario_run_timeline_run_id ON scenario_run_timeline (run_id, sequence);
