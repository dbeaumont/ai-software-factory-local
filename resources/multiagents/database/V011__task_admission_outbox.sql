-- Durable admission intent: PostgreSQL is written before Temporal and unfinished starts are reconciled.
CREATE TABLE task_admission_outbox (
    task_id          varchar(64) PRIMARY KEY REFERENCES tasks(task_id),
    attempt_id       varchar(128) NOT NULL,
    workflow_id      varchar(255) NOT NULL UNIQUE,
    status           varchar(16) NOT NULL CHECK (status IN ('PENDING', 'STARTED')),
    retry_count      integer NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    last_error_code  varchar(128),
    next_attempt_at  timestamptz NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint NOT NULL DEFAULT 0
);

CREATE INDEX task_admission_outbox_pending_idx
    ON task_admission_outbox(status, next_attempt_at, created_at);
