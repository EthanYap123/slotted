-- Append-only history of every change (PRD BR-32, BR-33, docs/ERD.md). Rows are written by the
-- services in the same transaction as the change they describe, so a change and its record are
-- committed together or not at all.

CREATE TABLE audit_log (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    -- Who made the change; null means the system (no-show release, waitlist promotion, the sweep).
    actor_id    uuid REFERENCES app_user (id),
    action      text NOT NULL,
    entity_type text NOT NULL,
    entity_id   uuid NOT NULL,
    -- The facility the change belongs to, so a facility's history can be found quickly.
    facility_id uuid,
    before      jsonb,
    after       jsonb,
    request_id  text,

    CONSTRAINT audit_entity_type_valid CHECK (entity_type IN
        ('BOOKING', 'WAITLIST_ENTRY', 'ROOM', 'FACILITY', 'OPENING_HOURS', 'BLACKOUT', 'USER')),
    CONSTRAINT audit_action_length CHECK (char_length(action) BETWEEN 1 AND 50),
    CONSTRAINT audit_has_values CHECK (before IS NOT NULL OR after IS NOT NULL),
    CONSTRAINT audit_request_id_length CHECK (char_length(request_id) <= 64)
);

CREATE INDEX audit_entity ON audit_log (entity_type, entity_id, occurred_at);
CREATE INDEX audit_facility_time ON audit_log (facility_id, occurred_at);
CREATE INDEX audit_actor_time ON audit_log (actor_id, occurred_at);
CREATE INDEX audit_time ON audit_log (occurred_at);

-- Records cannot be edited (BR-33). Deleting is left to the sweep, which removes old records.
CREATE FUNCTION audit_log_refuse_update() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log rows cannot be changed';
END;
$$;

CREATE TRIGGER audit_log_no_update
    BEFORE UPDATE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_refuse_update();
