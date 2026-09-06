CREATE TABLE a2a_task_associations (
    delegation_id      varchar(128) PRIMARY KEY,
    task_id            varchar(64) NOT NULL,
    attempt_id         varchar(128) NOT NULL,
    source_commit      char(40) NOT NULL,
    agent_role         varchar(64) NOT NULL,
    a2a_task_id        varchar(255) NOT NULL,
    a2a_context_id     varchar(255) NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (delegation_id, task_id, attempt_id, source_commit)
        REFERENCES delegations(delegation_id, task_id, attempt_id, source_commit),
    UNIQUE (agent_role, a2a_task_id),
    UNIQUE (agent_role, a2a_context_id, delegation_id)
);

CREATE INDEX a2a_task_associations_business_idx
    ON a2a_task_associations(task_id, attempt_id, delegation_id);
