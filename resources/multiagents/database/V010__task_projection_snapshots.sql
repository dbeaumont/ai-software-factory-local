-- PostgreSQL stores only the verified Evidence reference for the complete TaskView snapshot.
CREATE TABLE task_projection_snapshots (
    task_id          varchar(64) PRIMARY KEY REFERENCES tasks(task_id),
    attempt_id       varchar(128) NOT NULL,
    snapshot_uri     varchar(1024) NOT NULL,
    snapshot_digest  char(64) NOT NULL,
    projected_at     timestamptz NOT NULL,
    version          bigint NOT NULL DEFAULT 0,
    CHECK (snapshot_digest ~ '^[0-9a-f]{64}$'),
    UNIQUE (task_id, attempt_id, snapshot_uri, snapshot_digest)
);

CREATE INDEX task_projection_snapshots_age_idx ON task_projection_snapshots(projected_at);
