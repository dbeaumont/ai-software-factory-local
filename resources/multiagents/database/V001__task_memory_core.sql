-- Authoritative metadata model for task-memory projections. Raw content belongs in Evidence MCP.
CREATE TABLE tasks (
    task_id             varchar(64) PRIMARY KEY,
    repository_id       varchar(63) NOT NULL,
    current_attempt_id  varchar(128) NOT NULL,
    source_commit       char(40) NOT NULL,
    requirement_digest  char(64) NOT NULL,
    status              varchar(48) NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint NOT NULL DEFAULT 0,
    CHECK (source_commit ~ '^[0-9a-f]{40}$'),
    CHECK (requirement_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE workflow_runs (
    workflow_run_id  varchar(128) PRIMARY KEY,
    workflow_id      varchar(255) NOT NULL,
    temporal_run_id  uuid NOT NULL,
    task_id          varchar(64) NOT NULL REFERENCES tasks(task_id),
    attempt_id       varchar(128) NOT NULL,
    source_commit    char(40) NOT NULL,
    status           varchar(48) NOT NULL,
    started_at       timestamptz NOT NULL,
    completed_at     timestamptz,
    version          bigint NOT NULL DEFAULT 0,
    UNIQUE (task_id, attempt_id),
    UNIQUE (workflow_id, temporal_run_id)
);

CREATE TABLE delegations (
    delegation_id          varchar(128) PRIMARY KEY,
    parent_delegation_id   varchar(128) REFERENCES delegations(delegation_id),
    workflow_run_id        varchar(128) NOT NULL REFERENCES workflow_runs(workflow_run_id),
    task_id                varchar(64) NOT NULL REFERENCES tasks(task_id),
    attempt_id             varchar(128) NOT NULL,
    source_commit          char(40) NOT NULL,
    role                   varchar(64) NOT NULL,
    objective_digest       char(64) NOT NULL,
    budget_tokens          bigint NOT NULL CHECK (budget_tokens > 0),
    budget_cost_micros     bigint NOT NULL CHECK (budget_cost_micros >= 0),
    budget_turns           integer NOT NULL CHECK (budget_turns > 0),
    status                 varchar(48) NOT NULL,
    created_at             timestamptz NOT NULL,
    completed_at           timestamptz,
    version                bigint NOT NULL DEFAULT 0
);

CREATE TABLE agent_runs (
    agent_run_id      varchar(128) PRIMARY KEY,
    delegation_id     varchar(128) NOT NULL REFERENCES delegations(delegation_id),
    task_id           varchar(64) NOT NULL REFERENCES tasks(task_id),
    attempt_id        varchar(128) NOT NULL,
    source_commit     char(40) NOT NULL,
    role              varchar(64) NOT NULL,
    agent_version     varchar(64) NOT NULL,
    prompt_digest     char(64) NOT NULL,
    status            varchar(48) NOT NULL,
    started_at        timestamptz NOT NULL,
    completed_at      timestamptz,
    version           bigint NOT NULL DEFAULT 0
);

CREATE TABLE decisions (
    decision_id       varchar(128) PRIMARY KEY,
    workflow_run_id   varchar(128) NOT NULL REFERENCES workflow_runs(workflow_run_id),
    delegation_id     varchar(128) REFERENCES delegations(delegation_id),
    task_id           varchar(64) NOT NULL REFERENCES tasks(task_id),
    attempt_id        varchar(128) NOT NULL,
    source_commit     char(40) NOT NULL,
    decision_type     varchar(48) NOT NULL,
    decision          varchar(48) NOT NULL,
    actor              varchar(128) NOT NULL,
    rationale_digest  char(64) NOT NULL,
    decided_at        timestamptz NOT NULL,
    version           bigint NOT NULL DEFAULT 0
);

CREATE TABLE approvals (
    approval_id      varchar(128) PRIMARY KEY,
    workflow_run_id  varchar(128) NOT NULL REFERENCES workflow_runs(workflow_run_id),
    task_id          varchar(64) NOT NULL REFERENCES tasks(task_id),
    attempt_id       varchar(128) NOT NULL,
    source_commit    char(40) NOT NULL,
    manifest_id      char(64) NOT NULL,
    manifest_digest  char(64) NOT NULL,
    decision         varchar(32) NOT NULL,
    approver         varchar(128) NOT NULL,
    decided_at       timestamptz NOT NULL,
    version          bigint NOT NULL DEFAULT 0,
    UNIQUE (task_id, attempt_id, manifest_id, approver)
);

CREATE INDEX workflow_runs_task_idx ON workflow_runs(task_id, started_at);
CREATE INDEX delegations_workflow_idx ON delegations(workflow_run_id, created_at);
CREATE INDEX agent_runs_delegation_idx ON agent_runs(delegation_id, started_at);
CREATE INDEX decisions_workflow_idx ON decisions(workflow_run_id, decided_at);
CREATE INDEX approvals_workflow_idx ON approvals(workflow_run_id, decided_at);

