ALTER TABLE a2a_task_associations ADD COLUMN workflow_id varchar(255);
ALTER TABLE a2a_task_associations ADD COLUMN workflow_run_id varchar(128);
ALTER TABLE a2a_task_associations ADD COLUMN message_id varchar(200);
ALTER TABLE a2a_task_associations ADD COLUMN agent_card_digest char(64);

UPDATE a2a_task_associations association
SET workflow_run_id = delegation.workflow_run_id,
    workflow_id = workflow.workflow_id
FROM delegations delegation
JOIN workflow_runs workflow ON workflow.workflow_run_id = delegation.workflow_run_id
WHERE delegation.delegation_id = association.delegation_id;

ALTER TABLE a2a_task_associations
    ADD CONSTRAINT a2a_task_associations_complete_correlation CHECK (
        workflow_id IS NOT NULL
        AND workflow_run_id IS NOT NULL
        AND ((message_id IS NULL AND agent_card_digest IS NULL)
             OR (message_id IS NOT NULL AND agent_card_digest ~ '^[0-9a-f]{64}$'))
    );

CREATE UNIQUE INDEX a2a_task_associations_message_idx
    ON a2a_task_associations(agent_role, message_id)
    WHERE message_id IS NOT NULL;
