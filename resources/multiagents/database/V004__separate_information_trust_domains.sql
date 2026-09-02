CREATE TYPE information_kind AS ENUM (
    'VERIFIED_EVIDENCE',
    'UNTRUSTED_INPUT',
    'AGENT_CONCLUSION',
    'POLICY_DECISION'
);

ALTER TABLE artifacts
    ADD COLUMN information_kind information_kind NOT NULL;

ALTER TABLE evidence_refs
    ADD COLUMN information_kind information_kind NOT NULL;

ALTER TABLE decisions
    ADD COLUMN information_kind information_kind NOT NULL;

ALTER TABLE artifacts
    ADD CONSTRAINT artifacts_verified_status_ck CHECK (
        information_kind <> 'VERIFIED_EVIDENCE' OR status = 'COMPLETE'
    );

ALTER TABLE evidence_refs
    ADD CONSTRAINT evidence_refs_verified_status_ck CHECK (
        information_kind <> 'VERIFIED_EVIDENCE' OR verification = 'VERIFIED'
    );

ALTER TABLE decisions
    ADD CONSTRAINT decisions_policy_actor_ck CHECK (
        information_kind <> 'POLICY_DECISION' OR decision_type = 'POLICY'
    );

CREATE INDEX artifacts_information_kind_idx
    ON artifacts(task_id, attempt_id, information_kind);

CREATE INDEX decisions_information_kind_idx
    ON decisions(workflow_run_id, information_kind, decided_at);

