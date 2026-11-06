-- Recurring bookings (PRD BR-20 to BR-23, docs/ERD.md). Each occurrence is a normal booking row, so
-- every rule and the exclusion constraint apply to it too.

CREATE TABLE booking_series (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               uuid NOT NULL REFERENCES app_user (id),
    room_id               uuid NOT NULL REFERENCES room (id),
    frequency             text NOT NULL,
    occurrences_requested smallint NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    cancelled_at          timestamptz,

    CONSTRAINT booking_series_frequency_valid CHECK (frequency IN ('DAILY', 'WEEKLY')),
    CONSTRAINT booking_series_occurrences_valid CHECK (occurrences_requested >= 2)
);

ALTER TABLE booking ADD COLUMN series_id uuid REFERENCES booking_series (id);

CREATE INDEX booking_series_member ON booking (series_id) WHERE series_id IS NOT NULL;
