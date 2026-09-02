-- Append-only, metadata-only journal for traceable contradiction arbitration.
CREATE TABLE arbitration_records (
    arbitration_id    varchar(128) PRIMARY KEY,
    task_id           varchar(64) NOT NULL,
    attempt_id        varchar(128) NOT NULL,
    source_commit     char(40) NOT NULL,
    contradiction_id varchar(128) NOT NULL,
    rule_id           varchar(128) NOT NULL,
    rule_version      varchar(32) NOT NULL,
    decision          varchar(128) NOT NULL,
    author            varchar(256) NOT NULL,
    author_type       varchar(32) NOT NULL CHECK (author_type IN
                         ('WORKFLOW', 'POLICY', 'AGENT', 'INDEPENDENT_REVIEWER', 'HUMAN')),
    record_digest     char(64) NOT NULL,
    decided_at        timestamptz NOT NULL,
    FOREIGN KEY (task_id, attempt_id, source_commit)
        REFERENCES tasks(task_id, attempt_id, source_commit),
    UNIQUE (task_id, attempt_id, contradiction_id, record_digest)
);

CREATE TABLE arbitration_inputs (
    arbitration_id varchar(128) NOT NULL REFERENCES arbitration_records(arbitration_id),
    input_id       varchar(128) NOT NULL,
    input_digest   char(64) NOT NULL,
    PRIMARY KEY (arbitration_id, input_id)
);

CREATE TABLE arbitration_evidence (
    arbitration_id varchar(128) NOT NULL REFERENCES arbitration_records(arbitration_id),
    evidence_uri   varchar(1024) NOT NULL,
    evidence_digest char(64) NOT NULL,
    PRIMARY KEY (arbitration_id, evidence_uri)
);

CREATE INDEX arbitration_records_task_idx
    ON arbitration_records(task_id, attempt_id, decided_at);

REVOKE UPDATE, DELETE, TRUNCATE ON arbitration_records, arbitration_inputs, arbitration_evidence FROM PUBLIC;
