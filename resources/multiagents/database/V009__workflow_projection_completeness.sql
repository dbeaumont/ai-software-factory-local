-- Complete the rebuildable application projection needed by the Temporal cutover.
CREATE TABLE workflow_attempts (
    task_id             varchar(64) NOT NULL REFERENCES tasks(task_id),
    attempt_id          varchar(128) NOT NULL,
    previous_attempt_id varchar(128),
    source_commit       char(40) NOT NULL,
    reason_digest       char(64),
    actor               varchar(256),
    status              varchar(48) NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (task_id, attempt_id),
    FOREIGN KEY (task_id, previous_attempt_id) REFERENCES workflow_attempts(task_id, attempt_id),
    CHECK (source_commit ~ '^[0-9a-f]{40}$'),
    CHECK (reason_digest IS NULL OR reason_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE task_transitions (
    transition_id  varchar(128) PRIMARY KEY,
    task_id        varchar(64) NOT NULL,
    attempt_id     varchar(128) NOT NULL,
    source_commit  char(40) NOT NULL,
    previous_status varchar(48),
    next_status    varchar(48) NOT NULL,
    summary_digest char(64) NOT NULL,
    occurred_at    timestamptz NOT NULL,
    version        bigint NOT NULL DEFAULT 0,
    FOREIGN KEY (task_id, attempt_id) REFERENCES workflow_attempts(task_id, attempt_id),
    UNIQUE (task_id, attempt_id, transition_id)
);

CREATE TABLE human_actions (
    request_id       varchar(128) PRIMARY KEY,
    workflow_run_id  varchar(128) NOT NULL REFERENCES workflow_runs(workflow_run_id),
    task_id           varchar(64) NOT NULL,
    attempt_id        varchar(128) NOT NULL,
    source_commit     char(40) NOT NULL,
    contradiction_id  varchar(128),
    domain            varchar(32) NOT NULL,
    question_digest   char(64) NOT NULL,
    object_digest     char(64) NOT NULL,
    allowed_options   jsonb NOT NULL,
    status            varchar(32) NOT NULL,
    created_at        timestamptz NOT NULL,
    answered_at       timestamptz,
    version           bigint NOT NULL DEFAULT 0,
    FOREIGN KEY (task_id, attempt_id) REFERENCES workflow_attempts(task_id, attempt_id),
    UNIQUE (task_id, attempt_id, request_id)
);

CREATE TABLE pending_effects (
    effect_id          varchar(128) PRIMARY KEY,
    workflow_run_id    varchar(128) NOT NULL REFERENCES workflow_runs(workflow_run_id),
    task_id             varchar(64) NOT NULL,
    attempt_id          varchar(128) NOT NULL,
    source_commit       char(40) NOT NULL,
    tool_name           varchar(128) NOT NULL,
    arguments_digest    char(64) NOT NULL,
    manifest_id         char(64),
    manifest_digest     char(64),
    policy_decision     varchar(32) NOT NULL,
    confirmation_required boolean NOT NULL,
    status              varchar(32) NOT NULL,
    expires_at          timestamptz,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint NOT NULL DEFAULT 0,
    FOREIGN KEY (task_id, attempt_id) REFERENCES workflow_attempts(task_id, attempt_id),
    UNIQUE (task_id, attempt_id, effect_id),
    CHECK ((manifest_id IS NULL) = (manifest_digest IS NULL))
);

CREATE INDEX workflow_attempts_task_idx ON workflow_attempts(task_id, created_at);
CREATE INDEX task_transitions_attempt_idx ON task_transitions(task_id, attempt_id, occurred_at);
CREATE INDEX human_actions_pending_idx ON human_actions(task_id, attempt_id, status);
CREATE INDEX pending_effects_open_idx ON pending_effects(task_id, attempt_id, status);
