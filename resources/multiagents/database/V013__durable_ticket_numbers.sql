-- Human ticket numbers remain unique across restarts and concurrent orchestrator replicas.
CREATE SEQUENCE task_ticket_number_seq START WITH 1 INCREMENT BY 1 NO CYCLE;

ALTER TABLE tasks ADD COLUMN ticket_number varchar(32);

UPDATE tasks
   SET ticket_number = 'AF-' || to_char(nextval('task_ticket_number_seq'), 'FM0000')
 WHERE ticket_number IS NULL;

ALTER TABLE tasks
    ALTER COLUMN ticket_number SET NOT NULL,
    ADD CONSTRAINT tasks_ticket_number_uq UNIQUE (ticket_number),
    ADD CONSTRAINT tasks_ticket_number_format_ck CHECK (ticket_number ~ '^AF-[0-9]{4,}$');
