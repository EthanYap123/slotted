-- Students waiting for an exact slot (PRD BR-24 to BR-28, docs/ERD.md, ADR-0007).

CREATE TABLE waitlist_entry (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id             uuid NOT NULL REFERENCES room (id),
    user_id             uuid NOT NULL REFERENCES app_user (id),
    start_at            timestamptz NOT NULL,
    end_at              timestamptz NOT NULL,
    slot                tstzrange GENERATED ALWAYS AS (tstzrange(start_at, end_at, '[)')) STORED,
    status              text NOT NULL DEFAULT 'WAITING',
    promoted_booking_id uuid REFERENCES booking (id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    resolved_at         timestamptz,

    CONSTRAINT waitlist_valid_range CHECK (start_at < end_at),
    CONSTRAINT waitlist_status_valid
        CHECK (status IN ('WAITING', 'PROMOTED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT waitlist_promoted_consistent
        CHECK ((status = 'PROMOTED') = (promoted_booking_id IS NOT NULL)),
    CONSTRAINT waitlist_resolved_consistent
        CHECK ((status = 'WAITING') = (resolved_at IS NULL))
);

-- One waiting entry per student per exact slot
CREATE UNIQUE INDEX waitlist_one_per_slot
    ON waitlist_entry (user_id, room_id, slot) WHERE status = 'WAITING';
CREATE INDEX waitlist_queue ON waitlist_entry (room_id, status, start_at, created_at);
-- A student's own entries (BR-24 limit and "My bookings")
CREATE INDEX waitlist_user_start ON waitlist_entry (user_id, start_at);
