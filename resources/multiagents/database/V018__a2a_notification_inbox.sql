CREATE TABLE a2a_notification_inbox (
    agent_role           varchar(64) NOT NULL,
    a2a_task_id          varchar(255) NOT NULL,
    context_id           varchar(255) NOT NULL,
    transition_sequence  bigint NOT NULL CHECK (transition_sequence >= 0),
    task_state           varchar(32) NOT NULL,
    payload_digest       char(64) NOT NULL CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
    payload_json         jsonb NOT NULL,
    workflow_id          varchar(255) NOT NULL,
    workflow_run_id      varchar(128) NOT NULL,
    signal_status        varchar(16) NOT NULL CHECK (signal_status IN ('PENDING', 'SIGNALLED')),
    occurred_at          timestamptz NOT NULL,
    signalled_at         timestamptz,
    PRIMARY KEY (agent_role, a2a_task_id, transition_sequence),
    FOREIGN KEY (agent_role, a2a_task_id)
        REFERENCES a2a_task_associations(agent_role, a2a_task_id)
);

CREATE INDEX a2a_notification_inbox_pending_idx
    ON a2a_notification_inbox(signal_status, occurred_at);
