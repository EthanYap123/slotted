# ADR-0001: Prevent double bookings with a Postgres exclusion constraint

- **Status:** Accepted
- **Date:** 29 October 2026
- **Related:** [PRD BR-6, BR-8, BR-9](../PRD.md#5-business-rules), [ERD](../ERD.md), [Architecture: concurrent booking](../ARCHITECTURE.md#4-two-users-book-the-same-slot-at-the-same-time)

## Context

The core promise of Slotted is that a confirmed booking is never shared. Two students will regularly try to book the same popular slot at the same moment (for example when the 14-day window rolls over at midnight).

A naive "check, then insert" in application code is a race:

1. Request A reads: no overlapping booking for Room 1, 10:00-11:00.
2. Request B reads the same: no overlap.
3. A inserts. B inserts. Both succeed, and the room is double booked.

Under the default `READ COMMITTED` isolation, neither request sees the other's uncommitted row, so the check cannot catch it. Overlap is about **ranges**, not equal values, so a plain unique index on `(room_id, start_time)` does not help either: 10:00-11:00 and 10:30-11:30 overlap but have different starts.

Options considered:

1. **Pessimistic locking.** Lock something before checking, for example `SELECT ... FROM rooms WHERE id = ? FOR UPDATE`, so all bookings for a room run one at a time. Postgres cannot lock rows that do not exist yet, so the lock has to be on a parent row (the room) or an advisory lock.
2. **`SERIALIZABLE` isolation.** Postgres detects the conflicting read/write pattern and aborts one transaction, which must then be retried.
3. **Exclusion constraint** on a `tstzrange` column: the database itself refuses any row whose range overlaps an existing active row for the same room.

## Decision

Use an exclusion constraint as the source of truth for room overlap:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE booking
  ADD CONSTRAINT booking_no_overlap
  EXCLUDE USING gist (room_id WITH =, slot WITH &&)
  WHERE (status IN ('CONFIRMED', 'CHECKED_IN'));
```

- `slot` is a `tstzrange` with half-open bounds `[start, end)`, so back-to-back bookings do not overlap.
- `btree_gist` lets the GiST index combine plain equality (`room_id`) with range overlap (`&&`).
- The constraint is **partial**: cancelled and no-show bookings keep their original slot for history but stop blocking the room.
- The API maps SQLSTATE `23P01` (`exclusion_violation`) on this constraint to `409 Conflict` with error code `SLOT_TAKEN`, and offers to join the waitlist.

The application still checks overlap first so that the common case returns a friendly error without hitting the constraint. That check is only for the user's benefit; the constraint is what guarantees correctness.

**Per-student rules need a lock as well.** Quotas (3 upcoming bookings, 4 hours a day, BR-8) and "no two overlapping bookings for the same student" (BR-9) span many rows and rooms, which a constraint cannot express. Two requests from the same student (double-click, two tabs) could each pass the quota check. Every booking write therefore starts with:

```sql
SELECT id FROM app_user WHERE id = :userId FOR UPDATE;
```

This serialises writes **per student**, not per room, so students never wait on each other. The lock is held only for the short booking transaction.

## How it behaves under concurrency

When A and B insert overlapping rows at the same time, B's insert waits on A's uncommitted index entry. If A commits, B fails with `23P01`. If A rolls back, B succeeds. Exactly one wins, with no retries and no extra round trips. The [sequence diagram in ARCHITECTURE.md](../ARCHITECTURE.md#4-two-users-book-the-same-slot-at-the-same-time) shows the steps, and Phase 2 adds an integration test that fires many concurrent requests at one slot against a real Postgres (Testcontainers) and asserts exactly one booking exists.

## Consequences

**Good**

- Correct for every writer: the booking API, waitlist promotion, recurring series, admin scripts, data fixes in `psql` and future code paths that forget to lock.
- No serialisation of a whole room's bookings, so non-overlapping bookings in the same room proceed in parallel.
- The rule lives in a Flyway migration that is reviewed and tested like code.
- A clear, cheap failure mode (one SQLSTATE) instead of retry loops.

**Bad, and how we handle it**

- Postgres-specific (`tstzrange`, GiST, `btree_gist`). Accepted: Postgres is a fixed choice, and Testcontainers runs the same Postgres in tests instead of H2. Neon supports `btree_gist`.
- JPA does not map `tstzrange` out of the box. A small Hibernate `UserType` (or storing `start_at`/`end_at` columns plus a generated `slot` column) is needed. Decision for Phase 2: keep `start_at` and `end_at` as normal `timestamptz` columns and define `slot` as `tstzrange(start_at, end_at, '[)')` **generated always as stored**, so JPA only maps two plain timestamps.
- Errors arrive at commit/flush time, so the service must flush inside the transaction and translate the exception. Covered by one exception handler and tests.
- No-shows must change status to stop blocking. That conflicts with a sleeping server; see [ADR-0004](0004-lazy-evaluation-of-time-based-rules.md) for how writes release expired bookings first.

## Rejected options

- **Pessimistic lock on the room row:** correct if every code path remembers to take the lock, which is exactly the kind of rule that is forgotten later (for example in waitlist promotion or a batch job). It also makes all bookings in a room wait for each other.
- **`SERIALIZABLE` isolation:** correct, but the losing request needs retry logic, and serialisation failures can also hit unrelated transactions, which is harder to explain to users and to test.
- **Application-level lock (in-memory or Redis):** breaks as soon as there is more than one instance, and adds a service that has no free tier here.
