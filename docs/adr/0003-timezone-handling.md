# ADR-0003: Store instants in UTC, apply rules in Asia/Singapore

- **Status:** Accepted
- **Date:** 29 October 2026
- **Related:** [PRD BR-1, BR-3, BR-4, BR-8, BR-29](../PRD.md#5-business-rules)

## Context

Almost every business rule depends on local time: "on the hour or half hour", "inside opening hours", "4 hours per calendar day", "blackout on 12 November". Users are in Singapore. The servers are not: Render and Neon may run in another region, CI runs in UTC, and a developer's laptop or a reviewer's browser could be anywhere.

Typical bugs this ADR prevents:

- A booking at 00:30 SGT on Tuesday is 16:30 UTC on Monday, so a "per day" quota counted in UTC puts it on the wrong day.
- `LocalDateTime` columns silently take the server's zone; moving the database or JVM to another region shifts every booking.
- A browser in another zone shows 10:00 SGT as 03:00, and the user books the wrong slot.

Singapore has no daylight saving time today, but code should not depend on that.

## Decision

1. **Storage is UTC instants.** Every point in time is a `timestamptz` column (Postgres stores it as UTC) and an `Instant` or `OffsetDateTime` in Java. `LocalDateTime` is never used for a point in time. Bookings store `start_at`/`end_at` plus the generated `slot tstzrange` ([ADR-0001](0001-double-booking-prevention.md)).
2. **The JVM and JDBC run in UTC.** The container sets `-Duser.timezone=UTC`, and Hibernate sets `hibernate.jdbc.time_zone=UTC`, so behaviour does not depend on where the code runs.
3. **Business rules use the facility's zone.** Each facility has `time_zone` (an IANA ID). Only `Asia/Singapore` is accepted for now (PRD assumptions). Rules convert instants with `ZoneId.of(facility.timeZone)`:
   - Opening hours are stored as `LocalTime` per `DayOfWeek` and compared to the booking's local start and end.
   - "Per day" quotas group by the local date.
   - 30-minute alignment is checked on local time.
   - Blackout and date inputs from users (`LocalDate`, `LocalTime`) are converted to instants in that zone at the edge.
4. **A `Clock` is injected everywhere.** No `Instant.now()` or `LocalDate.now()` in domain code. Tests use a fixed clock, so time-dependent rules (booking window, check-in window, no-shows) are tested at exact boundaries such as 23:59 and 00:00 SGT.
5. **The API speaks ISO-8601 with an offset.** Responses use `2026-11-03T14:00:00+08:00` (instants rendered in the facility zone, so they read naturally). Requests must include an offset or `Z`; a time without one is rejected with `400` rather than guessed. Date-only parameters (`date=2026-11-03`) mean that date in the facility's zone.
6. **Postgres queries use the zone explicitly** where they group by local time, for example analytics: `date_trunc('hour', start_at AT TIME ZONE 'Asia/Singapore')`. The zone comes from the facility row, not the session.
7. **The web app always shows SGT,** whatever the browser's zone, using `Intl.DateTimeFormat` with `timeZone: 'Asia/Singapore'`, and labels times "SGT" where they could be misread. Date and time pickers build values in SGT and send them with `+08:00`.

## Consequences

- One rule to remember: instants inside, local time only at the edges and in rule checks.
- Tests can run on any machine or CI runner and give the same result; CI deliberately runs with the system zone set to something other than Singapore (for example `America/New_York`) to catch accidental use of the default zone.
- Supporting another zone later means allowing other values in `facility.time_zone` and checking the web app's display; the storage format does not change.
- Slightly more verbose code (passing `ZoneId` and `Clock` around) than calling `now()`.

## Rejected options

- **Store `timestamp without time zone` in SGT:** simple at first, but meaning depends on convention, breaks if any server or tool uses another zone, and makes range types and comparisons with `now()` error-prone.
- **Set the JVM or database default zone to Asia/Singapore:** hides the problem instead of fixing it, and breaks as soon as a library or test runs in another zone.
- **Show times in the browser's zone:** correct in theory, confusing in practice for a facility that only exists in Singapore.
