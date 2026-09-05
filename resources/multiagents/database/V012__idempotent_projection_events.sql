-- Stable Temporal activity IDs make projection retries exactly-once at the PostgreSQL boundary.
ALTER TABLE task_projection_snapshots
    ADD COLUMN last_event_position bigint NOT NULL DEFAULT 0,
    ADD COLUMN last_event_id varchar(255);

CREATE TABLE task_projection_events (
    projection_position bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    task_id              varchar(64) NOT NULL REFERENCES tasks(task_id),
    attempt_id           varchar(128) NOT NULL,
    event_id             varchar(255) NOT NULL,
    snapshot_digest      char(64) NOT NULL,
    projected_at         timestamptz NOT NULL,
    PRIMARY KEY (task_id, attempt_id, event_id),
    CHECK (snapshot_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX task_projection_events_cursor_idx
    ON task_projection_events(task_id, attempt_id, projection_position);
