-- Legacy import functions created before durable ticket numbers need a safe sequence-backed default.
ALTER TABLE tasks
    ALTER COLUMN ticket_number SET DEFAULT ('AF-' || to_char(nextval('task_ticket_number_seq'), 'FM0000'));
