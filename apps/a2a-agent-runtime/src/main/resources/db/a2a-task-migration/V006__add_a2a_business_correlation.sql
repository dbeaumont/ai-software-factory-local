ALTER TABLE a2a_agent_task ADD COLUMN business_task_id VARCHAR(128);
ALTER TABLE a2a_agent_task ADD COLUMN workflow_attempt_id VARCHAR(128);

-- Existing rows predate the split between the protocol task and the business task.
-- They retain their previous identity while every newly admitted task persists the exact correlation.
UPDATE a2a_agent_task SET business_task_id = task_id WHERE business_task_id IS NULL;
UPDATE a2a_agent_task SET workflow_attempt_id = 'attempt-1' WHERE workflow_attempt_id IS NULL;

ALTER TABLE a2a_agent_task ALTER COLUMN business_task_id SET NOT NULL;
ALTER TABLE a2a_agent_task ALTER COLUMN workflow_attempt_id SET NOT NULL;
