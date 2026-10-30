# Data model

PostgreSQL 17 (the same major version locally, in Testcontainers and on Neon). Schema changes are Flyway migrations in `api/src/main/resources/db/migration`. This page is the design; the migrations are the source of truth once they exist.

Conventions:

- Table names are singular (`booking`). `user` is a reserved word, so users live in `app_user`.
- Primary keys are `uuid` (`gen_random_uuid()`), except append-only logs, which use `bigint` identity.
- Every point in time is `timestamptz` ([ADR-0003](adr/0003-timezone-handling.md)). Wall-clock values (opening hours) are `time`.
- Enumerations are `text` with a `CHECK` constraint, so adding a value is a simple migration.
- Mutable rows have `created_at`, `updated_at` and a `version` column for optimistic locking in JPA.

## Diagram

```mermaid
erDiagram
    app_user ||--o{ refresh_token : "signs in with"
    app_user ||--o{ facility_manager : "manages via"
    facility ||--o{ facility_manager : "managed by"
    facility ||--o{ opening_hours : "opens on"
    facility ||--o{ room : contains
    facility ||--o{ blackout : "closed by"
    room ||--o{ blackout : "closed by"
    room ||--o{ booking : "booked as"
    app_user ||--o{ booking : makes
    booking_series |o--o{ booking : groups
    app_user ||--o{ booking_series : creates
    room ||--o{ booking_series : "repeats in"
    room ||--o{ waitlist_entry : "waited for"
    app_user ||--o{ waitlist_entry : joins
    waitlist_entry |o--o| booking : "promoted to"
    app_user |o--o{ audit_log : "acted (null = system)"

    app_user {
        uuid id PK
        citext email UK
        text password_hash
        text full_name
        text role "STUDENT | MANAGER | ADMIN"
        text status "ACTIVE | DISABLED"
        timestamptz suspension_cleared_at "admin lifted suspension"
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }
    refresh_token {
        uuid id PK
        uuid user_id FK
        text token_hash UK "SHA-256, never the token"
        uuid family_id
        timestamptz expires_at
        timestamptz revoked_at
        uuid replaced_by_id FK
        timestamptz created_at
    }
    facility {
        uuid id PK
        text name UK
        text description
        text location
        text time_zone "Asia/Singapore only"
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }
    facility_manager {
        uuid facility_id PK, FK
        uuid user_id PK, FK
        timestamptz assigned_at
    }
    opening_hours {
        uuid facility_id PK, FK
        smallint day_of_week PK "1 = Monday ... 7 = Sunday"
        time opens_at
        time closes_at
    }
    room {
        uuid id PK
        uuid facility_id FK
        text name "unique per facility"
        text type "STUDY_ROOM | SPORTS_COURT | MEETING_ROOM"
        int capacity
        int max_booking_minutes "30-240, default 120"
        boolean active
        text checkin_token UK "random, rotated by manager"
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }
    blackout {
        uuid id PK
        uuid facility_id FK
        uuid room_id FK "null = whole facility"
        timestamptz start_at
        timestamptz end_at
        tstzrange period "generated"
        text reason
        uuid created_by FK
        timestamptz created_at
    }
    booking_series {
        uuid id PK
        uuid user_id FK
        uuid room_id FK
        text frequency "DAILY | WEEKLY"
        smallint occurrences_requested
        timestamptz created_at
        timestamptz cancelled_at
    }
    booking {
        uuid id PK
        uuid room_id FK
        uuid user_id FK
        uuid series_id FK
        timestamptz start_at
        timestamptz end_at
        tstzrange slot "generated, [start_at, end_at)"
        text status "CONFIRMED | CHECKED_IN | CANCELLED | NO_SHOW"
        text source "DIRECT | SERIES | WAITLIST"
        smallint attendees
        text purpose
        timestamptz checked_in_at
        timestamptz released_at "no-show or ended early"
        timestamptz cancelled_at
        uuid cancelled_by FK
        text cancel_reason
        boolean late_cancellation
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }
    waitlist_entry {
        uuid id PK
        uuid room_id FK
        uuid user_id FK
        timestamptz start_at
        timestamptz end_at
        tstzrange slot "generated"
        text status "WAITING | PROMOTED | EXPIRED | CANCELLED"
        uuid promoted_booking_id FK
        timestamptz created_at
        timestamptz resolved_at
    }
    audit_log {
        bigint id PK
        timestamptz occurred_at
        uuid actor_id FK "null = system"
        text action "e.g. BOOKING_CANCELLED"
        text entity_type
        uuid entity_id
        uuid facility_id "for manager filtering"
        jsonb before
        jsonb after
        text request_id
    }
    email_outbox {
        bigint id PK
        text to_address
        text template
        jsonb payload
        text status "PENDING | SENT | FAILED | SKIPPED"
        smallint attempts
        timestamptz next_attempt_at
        text last_error
        timestamptz created_at
        timestamptz sent_at
    }
```

## Tables

| Table | Purpose |
| --- | --- |
| `app_user` | Accounts and roles. `suspension_cleared_at` lets an admin lift a no-show suspension: only no-shows after it count ([ADR-0004](adr/0004-lazy-evaluation-of-time-based-rules.md)). Suspension itself is derived, not stored. |
| `refresh_token` | Hashed refresh tokens with rotation chain (`replaced_by_id`) and family for reuse detection ([ADR-0002](adr/0002-authentication-approach.md)). |
| `facility` | A venue with its own time zone and opening hours. |
| `facility_manager` | Which managers manage which facilities (many-to-many). |
| `opening_hours` | One open period per weekday. No row for a day means closed. `closes_at > opens_at`, so a facility cannot be open past midnight (BR-4). |
| `room` | Bookable space. `checkin_token` is the secret in the room's QR code. It is a low-value secret (it only proves presence at a room), so it is stored as is in order to reprint the QR code. |
| `blackout` | Unbookable period for a room or a whole facility. |
| `booking_series` | Groups the occurrences of a recurring booking. Each occurrence is a normal `booking` row, so every booking rule and the exclusion constraint apply to occurrences too. |
| `booking` | One booking. Stored `status` is what was last written; effective status (no-show, finished) is derived at read time ([ADR-0004](adr/0004-lazy-evaluation-of-time-based-rules.md)). Ending a checked-in booking early (BR-15) moves `end_at` to the next 30-minute boundary and sets `released_at`, so the freed time stops being covered by the exclusion constraint; the original end time stays in the audit log. |
| `waitlist_entry` | A student waiting for an exact slot. Queue order is `created_at`, then `id`. |
| `audit_log` | Append-only history of every change (BR-32). |
| `email_outbox` | Emails written in the same transaction as the change, sent and retried by the dispatcher. |

Utilisation analytics (AD-4) are computed from `booking`, `opening_hours` and `blackout` by query; there is no separate analytics table at this scale.

## Key constraints

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS citext;

-- booking: one active booking per room per moment (ADR-0001)
CREATE TABLE booking (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id           uuid NOT NULL REFERENCES room (id),
    user_id           uuid NOT NULL REFERENCES app_user (id),
    series_id         uuid REFERENCES booking_series (id),
    start_at          timestamptz NOT NULL,
    end_at            timestamptz NOT NULL,
    slot              tstzrange GENERATED ALWAYS AS (tstzrange(start_at, end_at, '[)')) STORED,
    status            text NOT NULL DEFAULT 'CONFIRMED'
                      CHECK (status IN ('CONFIRMED', 'CHECKED_IN', 'CANCELLED', 'NO_SHOW')),
    source            text NOT NULL DEFAULT 'DIRECT'
                      CHECK (source IN ('DIRECT', 'SERIES', 'WAITLIST')),
    attendees         smallint NOT NULL CHECK (attendees >= 1),
    purpose           text CHECK (char_length(purpose) <= 200),
    checked_in_at     timestamptz,
    released_at       timestamptz,
    cancelled_at      timestamptz,
    cancelled_by      uuid REFERENCES app_user (id),
    cancel_reason     text CHECK (char_length(cancel_reason) <= 500),
    late_cancellation boolean NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0,

    CONSTRAINT booking_valid_range CHECK (start_at < end_at),
    -- a checked-in booking has a check-in time; only checked-in or later-cancelled bookings have one
    CONSTRAINT booking_checked_in_consistent
        CHECK (status <> 'CHECKED_IN' OR checked_in_at IS NOT NULL),
    CONSTRAINT booking_checked_in_status
        CHECK (checked_in_at IS NULL OR status IN ('CHECKED_IN', 'CANCELLED')),
    CONSTRAINT booking_cancelled_consistent
        CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CONSTRAINT booking_no_overlap
        EXCLUDE USING gist (room_id WITH =, slot WITH &&)
        WHERE (status IN ('CONFIRMED', 'CHECKED_IN'))
);

-- Settle step and availability (ADR-0004)
CREATE INDEX booking_room_status_start ON booking (room_id, status, start_at);
-- My bookings, quotas and the per-student overlap check (BR-8, BR-9)
CREATE INDEX booking_user_start ON booking (user_id, start_at);

-- One waiting entry per student per exact slot
CREATE UNIQUE INDEX waitlist_one_per_slot
    ON waitlist_entry (user_id, room_id, slot) WHERE status = 'WAITING';
CREATE INDEX waitlist_queue ON waitlist_entry (room_id, status, start_at, created_at);

-- Room names unique within a facility, case-insensitive
CREATE UNIQUE INDEX room_name_per_facility ON room (facility_id, lower(name));
-- Lets blackout reference (room_id, facility_id) so a room blackout cannot point at another facility's room
ALTER TABLE room ADD CONSTRAINT room_id_facility UNIQUE (id, facility_id);
ALTER TABLE blackout ADD CONSTRAINT blackout_room_in_facility
    FOREIGN KEY (room_id, facility_id) REFERENCES room (id, facility_id);

CREATE INDEX blackout_period ON blackout USING gist (facility_id, period);
CREATE INDEX audit_entity ON audit_log (entity_type, entity_id, occurred_at);
CREATE INDEX audit_facility_time ON audit_log (facility_id, occurred_at);
CREATE INDEX outbox_pending ON email_outbox (next_attempt_at) WHERE status = 'PENDING';
```

Other checks: `room.capacity BETWEEN 1 AND 500`, `room.max_booking_minutes BETWEEN 30 AND 240 AND max_booking_minutes % 30 = 0`, `opening_hours.day_of_week BETWEEN 1 AND 7`, `opening_hours.closes_at > opens_at`, `facility.time_zone = 'Asia/Singapore'`.

## Rules the database does not enforce

These span rows or depend on configuration, so the service layer enforces them under the per-student row lock from [ADR-0001](adr/0001-double-booking-prevention.md):

- 30-minute alignment, booking length and booking window (BR-1 to BR-3), since they depend on the facility's zone and config.
- Opening hours and blackouts (BR-4, BR-5).
- Student quotas and no overlapping bookings for one student across rooms (BR-8, BR-9).
- Attendees within room capacity (BR-7).

## Deletion policy

- Bookings, waitlist entries and audit records are never hard-deleted by users; cancelling changes status.
- Rooms and facilities with bookings are deactivated, not deleted.
- The maintenance job removes expired refresh tokens, sent outbox rows after 30 days and audit records after 180 days (BR-33).
