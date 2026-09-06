ALTER TABLE a2a_agent_task_history ADD COLUMN task_version BIGINT;

UPDATE a2a_agent_task_history history
SET task_version = (
    SELECT COUNT(*) - 1
    FROM a2a_agent_task_history preceding
    WHERE preceding.task_id = history.task_id
      AND preceding.sequence_id <= history.sequence_id
);

ALTER TABLE a2a_agent_task_history ALTER COLUMN task_version SET NOT NULL;
ALTER TABLE a2a_agent_task_history
    ADD CONSTRAINT a2a_agent_task_history_version_unique UNIQUE (task_id, task_version);
