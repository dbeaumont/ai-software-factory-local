CREATE TABLE a2a_agent_cancellation_outbox (
    cancellation_id VARCHAR(256) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL UNIQUE REFERENCES a2a_agent_task(task_id) ON DELETE CASCADE,
    context_id VARCHAR(64) NOT NULL,
    agent_role VARCHAR(64) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    acknowledged_at TIMESTAMP
);

CREATE INDEX idx_a2a_agent_cancellation_pending
    ON a2a_agent_cancellation_outbox (agent_role, acknowledged_at, occurred_at, cancellation_id);
