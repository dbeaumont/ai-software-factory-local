-- Metadata only: artifact bytes remain authoritative in Evidence MCP.
CREATE TABLE artifacts (
    artifact_id       varchar(128) PRIMARY KEY,
    task_id           varchar(64) NOT NULL REFERENCES tasks(task_id),
    attempt_id        varchar(128) NOT NULL,
    source_commit     char(40) NOT NULL,
    artifact_type     varchar(64) NOT NULL,
    evidence_uri      varchar(1024) NOT NULL,
    digest            char(64) NOT NULL,
    status            varchar(32) NOT NULL,
    classification    varchar(32) NOT NULL,
    trust_domain      varchar(32) NOT NULL,
    media_type        varchar(128) NOT NULL,
    size_bytes        bigint NOT NULL CHECK (size_bytes >= 0),
    created_at        timestamptz NOT NULL,
    retain_until      timestamptz NOT NULL,
    version           bigint NOT NULL DEFAULT 0,
    UNIQUE (task_id, attempt_id, evidence_uri, digest)
);

CREATE TABLE evidence_refs (
    evidence_ref_id  varchar(128) PRIMARY KEY,
    artifact_id      varchar(128) NOT NULL REFERENCES artifacts(artifact_id),
    workflow_run_id  varchar(128) NOT NULL REFERENCES workflow_runs(workflow_run_id),
    delegation_id    varchar(128) REFERENCES delegations(delegation_id),
    decision_id      varchar(128) REFERENCES decisions(decision_id),
    task_id          varchar(64) NOT NULL REFERENCES tasks(task_id),
    attempt_id       varchar(128) NOT NULL,
    source_commit    char(40) NOT NULL,
    purpose          varchar(64) NOT NULL,
    verification     varchar(32) NOT NULL,
    verified_at      timestamptz,
    created_at       timestamptz NOT NULL,
    version          bigint NOT NULL DEFAULT 0
);

CREATE TABLE contradictions (
    contradiction_id  varchar(128) PRIMARY KEY,
    workflow_run_id   varchar(128) NOT NULL REFERENCES workflow_runs(workflow_run_id),
    task_id           varchar(64) NOT NULL REFERENCES tasks(task_id),
    attempt_id        varchar(128) NOT NULL,
    source_commit     char(40) NOT NULL,
    left_ref_id       varchar(128) NOT NULL REFERENCES evidence_refs(evidence_ref_id),
    right_ref_id      varchar(128) NOT NULL REFERENCES evidence_refs(evidence_ref_id),
    status            varchar(32) NOT NULL,
    resolution        varchar(64),
    resolved_by       varchar(128),
    created_at        timestamptz NOT NULL,
    resolved_at       timestamptz,
    version           bigint NOT NULL DEFAULT 0,
    CHECK (left_ref_id <> right_ref_id)
);

CREATE TABLE budget_usage (
    budget_usage_id   varchar(128) PRIMARY KEY,
    delegation_id     varchar(128) NOT NULL REFERENCES delegations(delegation_id),
    agent_run_id      varchar(128) REFERENCES agent_runs(agent_run_id),
    task_id           varchar(64) NOT NULL REFERENCES tasks(task_id),
    attempt_id        varchar(128) NOT NULL,
    source_commit     char(40) NOT NULL,
    sequence_number   integer NOT NULL CHECK (sequence_number > 0),
    tokens_used       bigint NOT NULL CHECK (tokens_used >= 0),
    cost_micros       bigint NOT NULL CHECK (cost_micros >= 0),
    turns_used        integer NOT NULL CHECK (turns_used >= 0),
    recorded_at       timestamptz NOT NULL,
    UNIQUE (delegation_id, sequence_number)
);

CREATE TABLE tool_invocations (
    tool_invocation_id  varchar(128) PRIMARY KEY,
    agent_run_id        varchar(128) NOT NULL REFERENCES agent_runs(agent_run_id),
    delegation_id       varchar(128) NOT NULL REFERENCES delegations(delegation_id),
    task_id             varchar(64) NOT NULL REFERENCES tasks(task_id),
    attempt_id          varchar(128) NOT NULL,
    source_commit       char(40) NOT NULL,
    server_name         varchar(128) NOT NULL,
    tool_name           varchar(128) NOT NULL,
    operation_id        varchar(128) NOT NULL,
    idempotency_key     varchar(200),
    request_digest      char(64) NOT NULL,
    response_digest     char(64),
    status              varchar(32) NOT NULL,
    started_at          timestamptz NOT NULL,
    completed_at        timestamptz,
    version             bigint NOT NULL DEFAULT 0,
    UNIQUE (task_id, attempt_id, operation_id)
);

CREATE INDEX artifacts_task_idx ON artifacts(task_id, attempt_id, artifact_type);
CREATE INDEX evidence_refs_workflow_idx ON evidence_refs(workflow_run_id, purpose);
CREATE INDEX contradictions_open_idx ON contradictions(workflow_run_id, status);
CREATE INDEX budget_usage_delegation_idx ON budget_usage(delegation_id, sequence_number);
CREATE INDEX tool_invocations_agent_idx ON tool_invocations(agent_run_id, started_at);

