CREATE TABLE a2a_agent_task_message (
    message_id VARCHAR(128) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL REFERENCES a2a_agent_task(task_id) ON DELETE CASCADE,
    message_digest CHAR(64) NOT NULL,
    envelope_json TEXT NOT NULL,
    accepted_at TIMESTAMP NOT NULL
);

INSERT INTO a2a_agent_task_message (message_id, task_id, message_digest, envelope_json, accepted_at)
SELECT message_id, task_id, message_digest, envelope_json, submitted_at
FROM a2a_agent_task;

CREATE INDEX idx_a2a_agent_task_message_task
    ON a2a_agent_task_message (task_id, accepted_at, message_id);
