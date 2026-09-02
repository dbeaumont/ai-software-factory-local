-- Compatibility metadata for terminal tasks imported from the former in-memory model.
-- The complete immutable TaskView stays in Evidence MCP, never in PostgreSQL.
CREATE TABLE legacy_task_imports (
    task_id                  varchar(64) PRIMARY KEY REFERENCES tasks(task_id),
    attempt_id               varchar(128) NOT NULL,
    source_commit            char(40) NOT NULL,
    source_commit_verified   boolean NOT NULL,
    legacy_status            varchar(48) NOT NULL,
    snapshot_uri             varchar(1024) NOT NULL,
    snapshot_digest          char(64) NOT NULL,
    snapshot_classification  varchar(32) NOT NULL,
    migrated_at              timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (legacy_status IN ('PR_CREATED', 'FAILED')),
    CHECK (snapshot_digest ~ '^[0-9a-f]{64}$'),
    UNIQUE (task_id, attempt_id, snapshot_uri, snapshot_digest)
);

-- Re-imports are idempotent only if they describe exactly the same archived terminal task.
CREATE FUNCTION import_legacy_task(
    imported_task_id varchar,
    imported_repository_id varchar,
    imported_attempt_id varchar,
    imported_source_commit char(40),
    imported_source_commit_verified boolean,
    imported_requirement_digest char(64),
    imported_target_status varchar,
    imported_legacy_status varchar,
    imported_created_at timestamptz,
    imported_updated_at timestamptz,
    imported_snapshot_uri varchar,
    imported_snapshot_digest char(64),
    imported_snapshot_classification varchar
) RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    IF imported_legacy_status NOT IN ('PR_CREATED', 'FAILED')
       OR imported_target_status NOT IN ('COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION 'only terminal legacy tasks can be imported' USING ERRCODE = '23514';
    END IF;

    INSERT INTO tasks(task_id, repository_id, current_attempt_id, source_commit, requirement_digest,
                      status, created_at, updated_at)
    VALUES (imported_task_id, imported_repository_id, imported_attempt_id, imported_source_commit,
            imported_requirement_digest, imported_target_status, imported_created_at, imported_updated_at)
    ON CONFLICT (task_id) DO NOTHING;

    IF NOT EXISTS (
        SELECT 1 FROM tasks
         WHERE task_id = imported_task_id
           AND repository_id = imported_repository_id
           AND current_attempt_id = imported_attempt_id
           AND source_commit = imported_source_commit
           AND requirement_digest = imported_requirement_digest
           AND status = imported_target_status
           AND created_at = imported_created_at
           AND updated_at = imported_updated_at
    ) THEN
        RAISE EXCEPTION 'divergent legacy task metadata for %', imported_task_id USING ERRCODE = '23505';
    END IF;

    INSERT INTO legacy_task_imports(task_id, attempt_id, source_commit, source_commit_verified,
                                    legacy_status, snapshot_uri, snapshot_digest, snapshot_classification)
    VALUES (imported_task_id, imported_attempt_id, imported_source_commit, imported_source_commit_verified,
            imported_legacy_status, imported_snapshot_uri, imported_snapshot_digest,
            imported_snapshot_classification)
    ON CONFLICT (task_id) DO UPDATE SET task_id = excluded.task_id
      WHERE legacy_task_imports.attempt_id = excluded.attempt_id
        AND legacy_task_imports.source_commit = excluded.source_commit
        AND legacy_task_imports.legacy_status = excluded.legacy_status
        AND legacy_task_imports.snapshot_uri = excluded.snapshot_uri
        AND legacy_task_imports.snapshot_digest = excluded.snapshot_digest;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'divergent legacy task re-import for %', imported_task_id USING ERRCODE = '23505';
    END IF;
END;
$$;
