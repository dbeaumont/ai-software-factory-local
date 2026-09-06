-- Operator-owned, durable global switch used during atomic cutovers and maintenance windows.
CREATE TABLE factory_admission_control (
    control_key     varchar(32) PRIMARY KEY,
    admissions_open boolean NOT NULL,
    reason          varchar(256) NOT NULL,
    revision        bigint NOT NULL CHECK (revision > 0),
    updated_at      timestamptz NOT NULL,
    CHECK (control_key = 'global'),
    CHECK (length(trim(reason)) > 0)
);

INSERT INTO factory_admission_control(control_key, admissions_open, reason, revision, updated_at)
VALUES ('global', true, 'normal_operation', 1, CURRENT_TIMESTAMP);
