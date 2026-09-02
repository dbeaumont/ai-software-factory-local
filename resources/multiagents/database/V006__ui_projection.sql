-- Rebuildable UI projection. No sensitive payload or evidence content is exposed.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_factory_ui_reader') THEN
        CREATE ROLE ai_factory_ui_reader NOLOGIN;
    END IF;
END
$$;

CREATE VIEW task_ui_projection AS
SELECT
    t.task_id,
    t.repository_id,
    t.current_attempt_id AS attempt_id,
    t.source_commit,
    t.status AS task_status,
    wr.workflow_id,
    wr.workflow_run_id,
    wr.status AS workflow_status,
    wr.started_at,
    wr.completed_at,
    COALESCE(d.delegation_count, 0) AS delegation_count,
    COALESCE(d.completed_delegation_count, 0) AS completed_delegation_count,
    COALESCE(b.tokens_used, 0) AS tokens_used,
    COALESCE(b.cost_micros, 0) AS cost_micros,
    COALESCE(b.turns_used, 0) AS turns_used,
    COALESCE(e.evidence_count, 0) AS evidence_count,
    COALESCE(e.verified_evidence_count, 0) AS verified_evidence_count,
    COALESCE(c.open_contradiction_count, 0) AS open_contradiction_count,
    t.updated_at,
    t.version
FROM tasks t
LEFT JOIN workflow_runs wr
  ON wr.task_id = t.task_id
 AND wr.attempt_id = t.current_attempt_id
 AND wr.source_commit = t.source_commit
LEFT JOIN LATERAL (
    SELECT count(*) AS delegation_count,
           count(*) FILTER (WHERE status = 'COMPLETED') AS completed_delegation_count
      FROM delegations
     WHERE workflow_run_id = wr.workflow_run_id
) d ON true
LEFT JOIN LATERAL (
    SELECT sum(tokens_used) AS tokens_used,
           sum(cost_micros) AS cost_micros,
           sum(turns_used) AS turns_used
      FROM budget_usage
     WHERE task_id = t.task_id AND attempt_id = t.current_attempt_id
) b ON true
LEFT JOIN LATERAL (
    SELECT count(*) AS evidence_count,
           count(*) FILTER (WHERE verification = 'VERIFIED') AS verified_evidence_count
      FROM evidence_refs
     WHERE workflow_run_id = wr.workflow_run_id
) e ON true
LEFT JOIN LATERAL (
    SELECT count(*) FILTER (WHERE status = 'OPEN') AS open_contradiction_count
      FROM contradictions
     WHERE workflow_run_id = wr.workflow_run_id
) c ON true;

CREATE VIEW delegation_ui_projection AS
SELECT delegation_id, parent_delegation_id, workflow_run_id, task_id, attempt_id, source_commit,
       role, status, budget_tokens, budget_cost_micros, budget_turns, created_at, completed_at, version
  FROM delegations;

CREATE VIEW evidence_ui_projection AS
SELECT artifact_id, task_id, attempt_id, source_commit, artifact_type, evidence_uri, digest,
       status, classification, information_kind, media_type, size_bytes, created_at, retain_until, version
  FROM artifacts;

REVOKE ALL ON task_ui_projection, delegation_ui_projection, evidence_ui_projection FROM PUBLIC;
GRANT SELECT ON task_ui_projection, delegation_ui_projection, evidence_ui_projection TO ai_factory_ui_reader;
