-- Periods when a room or a whole facility cannot be booked (PRD BR-30, docs/ERD.md).

CREATE TABLE blackout (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    facility_id uuid NOT NULL REFERENCES facility (id),
    -- Null means the whole facility.
    room_id     uuid,
    start_at    timestamptz NOT NULL,
    end_at      timestamptz NOT NULL,
    period      tstzrange GENERATED ALWAYS AS (tstzrange(start_at, end_at, '[)')) STORED,
    reason      text NOT NULL,
    created_by  uuid NOT NULL REFERENCES app_user (id),
    created_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT blackout_valid_range CHECK (start_at < end_at),
    CONSTRAINT blackout_reason_length CHECK (char_length(reason) BETWEEN 1 AND 200),
    -- A room blackout cannot point at another facility's room.
    CONSTRAINT blackout_room_in_facility
        FOREIGN KEY (room_id, facility_id) REFERENCES room (id, facility_id)
);

CREATE INDEX blackout_period ON blackout USING gist (facility_id, period);
