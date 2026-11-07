# ADR-0007: Promote from the waitlist without waiting for students, in a transaction of its own

- **Status:** Accepted
- **Date:** 7 November 2026
- **Related:** [PRD BR-26, BR-27](../PRD.md), [ADR-0004](0004-lazy-evaluation-of-time-based-rules.md) (refines "writes first make it true"), [ADR-0006](0006-lock-the-room-while-booking.md) (refines "always lock in the same order"), issue #52

## Context

Promotion gives freed time to the first eligible waiting student. Building it exposed two problems with the earlier decisions.

**Lock order.** ADR-0006 makes every booking lock the student, then the room, so transactions cannot wait on each other in a cycle. Promotion has to work the other way round. It must hold the room while it decides who gets the time, and only then does it know which student's quotas to check. A booking holding student S and waiting for room R, and a promotion holding R and waiting for S, is a deadlock.

**Rollback.** ADR-0004 says a booking attempt settles overdue no-shows and promotes waiting students in its own transaction, "then the original write continues", and the person booking gets `SLOT_TAKEN`. But an error rolls that transaction back, including the promotion. The waiting student would lose the slot, and every later attempt would repeat the same work and undo it again.

## Decision

1. **Promotion never waits for a student.** While holding the room, it takes each student's lock with `FOR NO KEY UPDATE SKIP LOCKED`. If someone else holds the lock, that student is mid-request, so their entry stays `WAITING` and the next entry is tried.
2. **Every write to a room rescans its queue.** Promotion is not keyed to what was just freed. It goes through all waiting entries in the room, oldest first, and promotes any whose slot is completely free and whose student is eligible. So an entry skipped for any reason gets another chance at the next write to that room.
3. **Attempts settle and promote first, in a transaction of their own.** Before creating a booking or series or joining a waitlist, `BookingAttempts` calls `WaitlistQueue.settleAndPromote`, which locks the room, records no-shows, promotes and commits. The write then runs in its own transaction, so its failure cannot undo the promotion.
4. **Anything else that frees time promotes in the same transaction.** Cancelling, blackouts, opening-hour changes and disabling an account should commit together with the promotion or not at all, as BR-26 asks.
5. **Waitlist changes take the room's lock.** Joining, leaving and promoting are serialised per room. A student cannot leave an entry while promotion is giving them its slot, and `created_at` follows the order people really joined in.

## Consequences

- Promotion cannot deadlock against bookings, because it never holds a room while waiting for a student. `aStudentWhoIsBusyIsSkippedWithoutWaitingAndKeepsTheirPlace` holds a student's lock during a cancellation and requires it to finish. With promotion changed to wait, that test fails and Postgres logs real deadlocks.
- **A rare break in first come, first served.** If the first student in the queue is mid-request at the exact moment a slot frees, the next student gets it. Their request usually takes milliseconds, and the same request might have made them ineligible anyway. Waiting for them instead would risk the deadlock above. Accepted: the quota and overlap guarantees still hold in this case, because the skipped student is simply not promoted.
- A write to a room does slightly more work: one extra short transaction, and a scan of that room's queue. Queues are capped at three entries per student and rooms hold few overlapping slots, so this stays small.
- A no-show recorded in the instant between the two transactions could let the person booking take the time ahead of the queue. This needs a booking to cross its no-show time within those milliseconds, and the exclusion constraint still prevents any overlap. Accepted.

## Rejected options

- **Lock every waiting student before the room:** read the queue first, lock those students in ID order, then lock the room. This keeps the lock order, but a booking already holds its own student, so student locks could still be taken out of order. It would also make every booking in a popular room queue behind every student waiting for it.
- **Do not roll back on `ApiException`:** would keep the promotion, but commits whatever else the transaction did before failing, and cannot work once Postgres has aborted the transaction after an exclusion violation.
- **Retry on deadlock only:** `BookingAttempts` already retries, which is why an earlier version of the race test passed while Postgres was logging deadlocks. A cancellation would have needed retries too, and each one costs a one-second `deadlock_timeout` wait.
- **Promote only from the scheduled sweep:** a cancellation must promote immediately (ST-7), and the sweep can be an hour late (ADR-0004).
