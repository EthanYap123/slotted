# ADR-0006: Lock the room while booking to avoid deadlocks

- **Status:** Accepted
- **Date:** 2 November 2026
- **Related:** [ADR-0001](0001-double-booking-prevention.md) (refines its "how it behaves under concurrency" section), issue #39

## Context

ADR-0001 made a Postgres exclusion constraint the guarantee against double bookings and expected concurrent requests for one slot to resolve cleanly: the second insert waits for the first, then fails with `23P01` and gets `409 SLOT_TAKEN`, "with no retries".

The concurrency test written for issue #39 (16 students booking the same slot at the same moment) showed that this is only half true:

- **The guarantee held.** Every run ended with exactly one booking.
- **But most losers got a 500.** When several transactions insert conflicting rows at once, each can end up waiting for another's uncommitted index entry. Postgres detects the cycle (after `deadlock_timeout`, one second by default) and aborts one transaction with `40P01 deadlock detected`. In one run, 13 of 15 losing requests failed this way.
- **Retrying is not enough.** Retrying the aborted transaction after a short pause just rejoined the same knot of waiting transactions; most requests still deadlocked after three attempts.

## Decision

1. **Lock the room row before checking and inserting:** `SELECT ... FROM room WHERE id = ? FOR NO KEY UPDATE` (JPA `PESSIMISTIC_WRITE`), right after the per-student lock from ADR-0001 and before the settle step. Bookings for one room now take turns; the second request waits for the first to commit, then its normal overlap check sees the committed booking and returns `SLOT_TAKEN` without ever inserting.
2. **Always lock in the same order:** the booker's user row, then the room. Two booking transactions therefore cannot wait on each other in a cycle.
3. **Keep the exclusion constraint as the guarantee.** The lock only makes the common path orderly. Anything that writes bookings without taking the lock (a future feature, a script, a manual fix) is still refused by the database.
4. **Keep a small retry** (`BookingAttempts`, up to three attempts with a 10-60 ms random pause) for any lock failure that still happens, reporting `SLOT_TAKEN` if all attempts fail.

## Consequences

- Concurrent requests for one room get correct answers: one `201`, the rest `409 SLOT_TAKEN`. The concurrency tests pass on every run with no deadlocks logged.
- Booking in one room is serialised. Each booking transaction is a handful of indexed queries, so even a busy room handles many bookings per second, far beyond a campus's needs. Different rooms are not affected.
- Room edits by managers wait for in-flight bookings in that room, and the other way round. Both are short.
- This partly brings back the "lock the parent row" option ADR-0001 rejected, but only as a performance and error-quality measure, not as the source of correctness. ADR-0001's reasons for making the constraint the guarantee still stand.

## Rejected options

- **Retry only:** tried first; transactions kept deadlocking because the retry joined the same queue of waiting inserts.
- **Lower `deadlock_timeout`:** detects deadlocks sooner but still aborts transactions; it changes database-wide behaviour for a problem that ordering solves.
- **`SERIALIZABLE` isolation:** rejected in ADR-0001 for the same reasons; it would turn deadlocks into serialisation failures that need retries.
- **Advisory locks (`pg_advisory_xact_lock`)** keyed on the room: equivalent, but less visible in the code and in `pg_locks` than a row lock on the room itself.
