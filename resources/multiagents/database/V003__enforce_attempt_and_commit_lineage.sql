-- Composite lineage keys prevent cross-attempt and cross-commit references.
ALTER TABLE workflow_runs
    ADD CONSTRAINT workflow_runs_lineage_uq
    UNIQUE (workflow_run_id, task_id, attempt_id, source_commit),
    ADD CONSTRAINT workflow_runs_attempt_uq
    UNIQUE (task_id, attempt_id, source_commit);

ALTER TABLE delegations
    ADD CONSTRAINT delegations_lineage_uq
    UNIQUE (delegation_id, task_id, attempt_id, source_commit),
    ADD CONSTRAINT delegations_workflow_lineage_fk
    FOREIGN KEY (workflow_run_id, task_id, attempt_id, source_commit)
    REFERENCES workflow_runs(workflow_run_id, task_id, attempt_id, source_commit);

ALTER TABLE agent_runs
    ADD CONSTRAINT agent_runs_lineage_uq
    UNIQUE (agent_run_id, task_id, attempt_id, source_commit),
    ADD CONSTRAINT agent_runs_delegation_lineage_fk
    FOREIGN KEY (delegation_id, task_id, attempt_id, source_commit)
    REFERENCES delegations(delegation_id, task_id, attempt_id, source_commit);

ALTER TABLE decisions
    ADD CONSTRAINT decisions_workflow_lineage_fk
    FOREIGN KEY (workflow_run_id, task_id, attempt_id, source_commit)
    REFERENCES workflow_runs(workflow_run_id, task_id, attempt_id, source_commit);

ALTER TABLE approvals
    ADD CONSTRAINT approvals_workflow_lineage_fk
    FOREIGN KEY (workflow_run_id, task_id, attempt_id, source_commit)
    REFERENCES workflow_runs(workflow_run_id, task_id, attempt_id, source_commit);

ALTER TABLE artifacts
    ADD CONSTRAINT artifacts_lineage_uq
    UNIQUE (artifact_id, task_id, attempt_id, source_commit),
    ADD CONSTRAINT artifacts_workflow_lineage_fk
    FOREIGN KEY (task_id, attempt_id, source_commit)
    REFERENCES workflow_runs(task_id, attempt_id, source_commit);

ALTER TABLE evidence_refs
    ADD CONSTRAINT evidence_refs_workflow_lineage_fk
    FOREIGN KEY (workflow_run_id, task_id, attempt_id, source_commit)
    REFERENCES workflow_runs(workflow_run_id, task_id, attempt_id, source_commit),
    ADD CONSTRAINT evidence_refs_artifact_lineage_fk
    FOREIGN KEY (artifact_id, task_id, attempt_id, source_commit)
    REFERENCES artifacts(artifact_id, task_id, attempt_id, source_commit);

ALTER TABLE contradictions
    ADD CONSTRAINT contradictions_workflow_lineage_fk
    FOREIGN KEY (workflow_run_id, task_id, attempt_id, source_commit)
    REFERENCES workflow_runs(workflow_run_id, task_id, attempt_id, source_commit);

ALTER TABLE budget_usage
    ADD CONSTRAINT budget_usage_delegation_lineage_fk
    FOREIGN KEY (delegation_id, task_id, attempt_id, source_commit)
    REFERENCES delegations(delegation_id, task_id, attempt_id, source_commit),
    ADD CONSTRAINT budget_usage_agent_lineage_fk
    FOREIGN KEY (agent_run_id, task_id, attempt_id, source_commit)
    REFERENCES agent_runs(agent_run_id, task_id, attempt_id, source_commit);

ALTER TABLE tool_invocations
    ADD CONSTRAINT tool_invocations_delegation_lineage_fk
    FOREIGN KEY (delegation_id, task_id, attempt_id, source_commit)
    REFERENCES delegations(delegation_id, task_id, attempt_id, source_commit),
    ADD CONSTRAINT tool_invocations_agent_lineage_fk
    FOREIGN KEY (agent_run_id, task_id, attempt_id, source_commit)
    REFERENCES agent_runs(agent_run_id, task_id, attempt_id, source_commit);

