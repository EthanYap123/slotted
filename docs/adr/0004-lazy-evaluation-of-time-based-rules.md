# ADR-0004: Evaluate time-based rules lazily; a scheduled job only tidies up

- **Status:** Accepted
- **Date:** 29 October 2026
- **Related:** [PRD BR-17, BR-18, BR-26, BR-28, SY-1](../PRD.md), [ADR-0001](0001-double-booking-prevention.md), [COSTS.md](../COSTS.md)

## Context

Several rules depend on the clock, not on a user action:

| Rule | Happens when |
| --- | --- |
| No-show release (BR-17) | 15 minutes after a booking starts without a check-in |
| No-show suspension (BR-18) | When a student reaches 3 no-shows in 30 days |
| Waitlist expiry (BR-28) | When the waited-for slot starts |
| Waitlist promotion after a no-show (BR-26) | When a no-show frees a slot someone is waiting for |
| Sending queued emails | Soon after the change that queued them |

The usual design is a scheduler inside the app (`@Scheduled` every minute). That does not work here:

- The Render free instance **sleeps after 15 minutes without traffic** and takes about a minute to wake. A sleeping app runs no schedulers.
- Render cron jobs and background workers are not on the free plan.
- GitHub Actions schedules can call the app, but they run at most every 5 minutes, are often **delayed or dropped** at busy times, are **disabled after 60 days** without repository activity, and every call wakes the server and spends free instance hours ([COSTS.md](../COSTS.md)).

So a rule like "release at 10:15" cannot depend on code running at 10:15.

## Decision

**Time-based state is derived from stored facts and the current time whenever it is read or written. Nothing waits for a job.** The scheduled job only records what is already true and sends emails.

### 1. Derive, don't wait

Each time-based status is a pure function of stored columns and `now` (from the injected `Clock`, [ADR-0003](0003-timezone-handling.md)):

| Stored | Effective status at `now` |
| --- | --- |
| Booking `CONFIRMED`, `checked_in_at IS NULL`, `start_at + 15 min <= now` | `NO_SHOW` |
| Booking `CONFIRMED` or `CHECKED_IN`, `end_at <= now` | finished (shown as completed) |
| Waitlist entry `WAITING`, `start_at <= now` | `EXPIRED` |
| Student with 3 or more effective no-shows in the 30 days before `now`, latest one after `suspension_cleared_at` | suspended until latest no-show + 7 days |

The rule is written **once in SQL** as reusable predicates (a view `booking_effective` and a function used by queries) and **once in Java** (`BookingTimePolicy`), and a test runs both against the same fixtures at boundary times so they cannot drift.

### 2. Reads use the effective status

Availability, "My bookings", manager lists and analytics all query through the effective-status predicates. At 10:16, a booking for 10:00-12:00 with no check-in is shown as a no-show and the room shows free from 10:30 (the next 30-minute boundary), even if no job has run since 9 a.m.

### 3. Writes first make it true in the database

The exclusion constraint ([ADR-0001](0001-double-booking-prevention.md)) only looks at the stored `status`, so an overdue `CONFIRMED` row still blocks the room. Every write that touches a room's schedule (create booking, promote waitlist, create series) therefore starts its transaction with a **settle** step for that room:

```sql
UPDATE booking
   SET status = 'NO_SHOW', released_at = start_at + interval '15 minutes'
 WHERE room_id = :roomId
   AND status = 'CONFIRMED'
   AND checked_in_at IS NULL
   AND start_at + interval '15 minutes' <= :now
RETURNING id;
```

For each row returned, in the same transaction: write an audit record (actor "system"), run waitlist promotion for the freed range (so a waiting student gets priority over the person whose write triggered the settle), and queue the no-show email. Then the original write continues.

Settling is idempotent (a second run updates nothing) and safe under concurrency (two transactions settling the same row: one updates it, the other waits and then matches zero rows). Check-in uses the same predicate in reverse: it only succeeds if `now < start_at + 15 min`, checked in the `UPDATE ... WHERE` itself, so a check-in and a settle can never both win.

### 4. Emails go through an outbox

A change that should send email inserts a row into `email_outbox` **in the same transaction**. After commit, an in-process listener tries to send it straight away. If sending fails or the app stops first, the row stays `PENDING` and is retried later with backoff (up to 5 attempts). A booking is never rolled back because email failed.

### 5. The scheduled job tidies up

A GitHub Actions workflow calls:

```
POST /api/v1/internal/maintenance/sweep
X-Maintenance-Key: <secret>
```

The endpoint:

- compares the key in constant time with `MAINTENANCE_KEY` (a GitHub Actions secret and Render env var), returning `401` otherwise, and is excluded from JWT auth and rate-limited;
- takes `pg_try_advisory_lock` so overlapping runs skip instead of doubling up;
- settles overdue no-shows for **all** rooms (with promotion and emails, as above), marks expired waitlist entries, and queues suspension emails;
- sends pending outbox emails (respecting the daily cap in [COSTS.md](../COSTS.md));
- deletes expired refresh tokens and audit/outbox rows past retention;
- works in bounded batches and returns counts as JSON, so the workflow log shows what it did.

Schedule: production hourly at minute 17 (off the hour to avoid GitHub's busiest time), staging twice a day, plus `workflow_dispatch` for manual runs. The workflow uses `curl --retry 3 --max-time 120` because the first call may hit a cold start.

## What users see if the job is late or never runs

| Situation | Effect |
| --- | --- |
| Availability, booking, check-in, waitlist position | Always correct (rules 1-3) |
| No-show recorded in the database | At the next write to that room, or the next job |
| Waitlist promotion after a **cancellation** | Immediate (it happens inside the cancel transaction) |
| Waitlist promotion after a **no-show** | At the next write to that room or the next job. A read in between shows the slot free; a booking attempt in between triggers the settle, which promotes the waiting student first, and the person booking gets `SLOT_TAKEN`. |
| No-show and suspension emails | Delayed until the next settle or job |
| Confirmation and cancellation emails | Sent right after the request; retried by the job if that failed |

## Consequences

**Good**

- Correctness does not depend on uptime, schedulers or GitHub's queue; the server can sleep for days and still answer correctly on the first request.
- Fewer wake-ups means fewer free instance hours used.
- Time rules are pure functions of data and a clock, so they are easy to unit test at exact boundaries.

**Bad, and how we handle it**

- The same rule exists in SQL and Java. Mitigated by the shared boundary test above.
- Writes do a little extra work (the settle `UPDATE`), indexed by `(room_id, status, start_at)`, so it is cheap.
- Some side effects (no-show emails) can be up to about an hour late, or longer if GitHub delays the schedule. Accepted and documented in the README.
- Features that need an exact send time, such as "reminder 30 minutes before", are out of scope ([PRD](../PRD.md#9-out-of-scope)).
- If the repository is inactive for 60 days, GitHub disables the schedule. Correctness is unaffected; emails would wait for the next real request. Re-enabling is a step in `docs/DEPLOYMENT.md` (Phase 4).

## Rejected options

- **In-app `@Scheduled` jobs only:** do not run while the free instance sleeps.
- **Keep the server awake with frequent pings:** uses the 750 free hours quickly (two always-on services would need about 1,460) and goes against the host's intent.
- **Postgres `pg_cron`:** Neon suspends the database compute after 5 minutes idle, so database-side schedules have the same problem, and it would split business logic into the database.
