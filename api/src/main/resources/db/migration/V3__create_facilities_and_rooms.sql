-- Facilities, their managers and opening hours, and rooms (docs/ERD.md, PRD MG-1, MG-2, AD-2).

CREATE TABLE facility (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL,
    description text,
    location    text,
    time_zone   text NOT NULL DEFAULT 'Asia/Singapore',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0,

    CONSTRAINT facility_name_length CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT facility_description_length CHECK (char_length(description) <= 1000),
    CONSTRAINT facility_location_length CHECK (char_length(location) <= 200),
    -- One institution, one zone for now (PRD section 8, ADR-0003).
    CONSTRAINT facility_time_zone_supported CHECK (time_zone = 'Asia/Singapore')
);

CREATE UNIQUE INDEX facility_name_unique ON facility (lower(name));

CREATE TABLE facility_manager (
    facility_id uuid NOT NULL REFERENCES facility (id) ON DELETE CASCADE,
    user_id     uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    assigned_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (facility_id, user_id)
);

CREATE INDEX facility_manager_user ON facility_manager (user_id);

-- One open period per weekday; no row means closed that day (BR-29).
CREATE TABLE opening_hours (
    facility_id uuid NOT NULL REFERENCES facility (id) ON DELETE CASCADE,
    day_of_week smallint NOT NULL,
    opens_at    time NOT NULL,
    closes_at   time NOT NULL,
    PRIMARY KEY (facility_id, day_of_week),

    CONSTRAINT opening_hours_day_valid CHECK (day_of_week BETWEEN 1 AND 7),
    -- No overnight opening, so a booking can never cross midnight (BR-4).
    CONSTRAINT opening_hours_order CHECK (closes_at > opens_at),
    -- Bookings start and end on the hour or half hour (BR-1), so opening hours do too.
    CONSTRAINT opening_hours_on_half_hours CHECK (
        date_part('minute', opens_at) IN (0, 30) AND date_part('second', opens_at) = 0
        AND date_part('minute', closes_at) IN (0, 30) AND date_part('second', closes_at) = 0)
);

CREATE TABLE room (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    facility_id         uuid NOT NULL REFERENCES facility (id),
    name                text NOT NULL,
    type                text NOT NULL,
    capacity            integer NOT NULL,
    max_booking_minutes integer NOT NULL DEFAULT 120,
    active              boolean NOT NULL DEFAULT true,
    -- Secret in the room's QR code; proves presence at the room on check-in (BR-16).
    checkin_token       text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,

    CONSTRAINT room_checkin_token_unique UNIQUE (checkin_token),
    -- Lets a blackout reference (room_id, facility_id), so it cannot point at another facility's room.
    CONSTRAINT room_id_facility UNIQUE (id, facility_id),
    CONSTRAINT room_name_length CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT room_type_valid CHECK (type IN ('STUDY_ROOM', 'SPORTS_COURT', 'MEETING_ROOM')),
    CONSTRAINT room_capacity_range CHECK (capacity BETWEEN 1 AND 500),
    CONSTRAINT room_max_booking_minutes_valid
        CHECK (max_booking_minutes BETWEEN 30 AND 240 AND max_booking_minutes % 30 = 0)
);

CREATE UNIQUE INDEX room_name_per_facility ON room (facility_id, lower(name));
