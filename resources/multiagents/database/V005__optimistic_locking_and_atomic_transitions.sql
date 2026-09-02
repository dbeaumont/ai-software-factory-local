ALTER TABLE workflow_runs ADD COLUMN updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE delegations ADD COLUMN updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE agent_runs ADD COLUMN updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE FUNCTION valid_execution_transition(entity_type text, previous_status text, next_status text)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE entity_type
        WHEN 'task' THEN (previous_status, next_status) IN (
            ('CREATED', 'RUNNING'), ('RUNNING', 'WAITING_APPROVAL'),
            ('RUNNING', 'FAILED'), ('WAITING_APPROVAL', 'APPROVED'),
            ('WAITING_APPROVAL', 'CANCELLED'), ('APPROVED', 'COMPLETED')
        )
        WHEN 'workflow_run' THEN (previous_status, next_status) IN (
            ('CREATED', 'RUNNING'), ('RUNNING', 'CONTINUING_AS_NEW'),
            ('RUNNING', 'WAITING_HUMAN'), ('RUNNING', 'COMPLETED'),
            ('CONTINUING_AS_NEW', 'RUNNING'), ('WAITING_HUMAN', 'RUNNING'),
            ('RUNNING', 'FAILED'), ('RUNNING', 'CANCELLED')
        )
        WHEN 'delegation' THEN (previous_status, next_status) IN (
            ('PENDING', 'RUNNING'), ('RUNNING', 'COMPLETED'),
            ('RUNNING', 'FAILED'), ('RUNNING', 'CANCELLED')
        )
        WHEN 'agent_run' THEN (previous_status, next_status) IN (
            ('PENDING', 'RUNNING'), ('RUNNING', 'COMPLETED'),
            ('RUNNING', 'FAILED'), ('RUNNING', 'BUDGET_EXHAUSTED'),
            ('RUNNING', 'CANCELLED')
        )
        ELSE false
    END;
$$;

CREATE FUNCTION transition_execution(
    entity_type text,
    entity_id varchar,
    expected_version bigint,
    previous_status varchar,
    next_status varchar,
    transition_at timestamptz
) RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
    affected integer;
    resulting_version bigint;
BEGIN
    IF NOT valid_execution_transition(entity_type, previous_status, next_status) THEN
        RAISE EXCEPTION 'illegal % transition: % -> %', entity_type, previous_status, next_status
            USING ERRCODE = '23514';
    END IF;

    CASE entity_type
        WHEN 'task' THEN
            UPDATE tasks SET status = next_status, updated_at = transition_at, version = version + 1
             WHERE task_id = entity_id AND version = expected_version AND status = previous_status
             RETURNING version INTO resulting_version;
        WHEN 'workflow_run' THEN
            UPDATE workflow_runs SET status = next_status, updated_at = transition_at, version = version + 1
             WHERE workflow_run_id = entity_id AND version = expected_version AND status = previous_status
             RETURNING version INTO resulting_version;
        WHEN 'delegation' THEN
            UPDATE delegations SET status = next_status, updated_at = transition_at, version = version + 1
             WHERE delegation_id = entity_id AND version = expected_version AND status = previous_status
             RETURNING version INTO resulting_version;
        WHEN 'agent_run' THEN
            UPDATE agent_runs SET status = next_status, updated_at = transition_at, version = version + 1
             WHERE agent_run_id = entity_id AND version = expected_version AND status = previous_status
             RETURNING version INTO resulting_version;
        ELSE
            RAISE EXCEPTION 'unknown transition entity: %', entity_type USING ERRCODE = '22023';
    END CASE;

    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN
        RAISE EXCEPTION 'optimistic lock conflict for % % at version %',
            entity_type, entity_id, expected_version USING ERRCODE = '40001';
    END IF;
    RETURN resulting_version;
END;
$$;

