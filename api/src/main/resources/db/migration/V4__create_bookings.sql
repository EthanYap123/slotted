-- Bookings, with the database itself refusing double bookings (ADR-0001, docs/ERD.md).
-- Series and waitlist columns arrive with those features in Phase 3.

CREATE TABLE booking (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id           uuid NOT NULL REFERENCES room (id),
    user_id           uuid NOT NULL REFERENCES app_user (id),
    start_at          timestamptz NOT NULL,
    end_at            timestamptz NOT NULL,
    -- Half-open [start, end), so back-to-back bookings do not overlap.
    slot              tstzrange GENERATED ALWAYS AS (tstzrange(start_at, end_at, '[)')) STORED,
    status            text NOT NULL DEFAULT 'CONFIRMED',
    source            text NOT NULL DEFAULT 'DIRECT',
    attendees         smallint NOT NULL,
    purpose           text,
    checked_in_at     timestamptz,
    released_at       timestamptz,
    cancelled_at      timestamptz,
    cancelled_by      uuid REFERENCES app_user (id),
    cancel_reason     text,
    late_cancellation boolean NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0,

    CONSTRAINT booking_valid_range CHECK (start_at < end_at),
    CONSTRAINT booking_status_valid CHECK (status IN ('CONFIRMED', 'CHECKED_IN', 'CANCELLED', 'NO_SHOW')),
    CONSTRAINT booking_source_valid CHECK (source IN ('DIRECT', 'SERIES', 'WAITLIST')),
    CONSTRAINT booking_attendees_positive CHECK (attendees >= 1),
    CONSTRAINT booking_purpose_length CHECK (char_length(purpose) <= 200),
    CONSTRAINT booking_cancel_reason_length CHECK (char_length(cancel_reason) <= 500),
    CONSTRAINT booking_checked_in_consistent CHECK (status <> 'CHECKED_IN' OR checked_in_at IS NOT NULL),
    CONSTRAINT booking_checked_in_status CHECK (checked_in_at IS NULL OR status IN ('CHECKED_IN', 'CANCELLED')),
    CONSTRAINT booking_cancelled_consistent CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    -- The guarantee: no two active bookings for one room overlap in time (ADR-0001).
    CONSTRAINT booking_no_overlap
        EXCLUDE USING gist (room_id WITH =, slot WITH &&)
        WHERE (status IN ('CONFIRMED', 'CHECKED_IN'))
);

-- Settle step and availability (ADR-0004).
CREATE INDEX booking_room_status_start ON booking (room_id, status, start_at);
-- My bookings, quotas, overlap across rooms and no-show counts for one student (BR-8, BR-9, BR-18).
CREATE INDEX booking_user_start ON booking (user_id, start_at);
