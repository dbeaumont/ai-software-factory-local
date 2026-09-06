ALTER TABLE a2a_agent_task ADD COLUMN envelope_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE a2a_agent_task ADD COLUMN workflow_id VARCHAR(256);
ALTER TABLE a2a_agent_task ADD COLUMN workflow_run_id VARCHAR(128);

CREATE UNIQUE INDEX idx_a2a_agent_task_workflow
    ON a2a_agent_task (workflow_id);

CREATE TABLE a2a_agent_notification_outbox (
    notification_id VARCHAR(256) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL REFERENCES a2a_agent_task(task_id) ON DELETE CASCADE,
    context_id VARCHAR(64) NOT NULL,
    agent_role VARCHAR(64) NOT NULL,
    task_sequence BIGINT NOT NULL,
    task_state VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    acknowledged_at TIMESTAMP
);

CREATE INDEX idx_a2a_agent_notification_pending
    ON a2a_agent_notification_outbox (agent_role, acknowledged_at, occurred_at, notification_id);
