# ADR-0008: Record audit changes in the services, in the same transaction

- **Status:** Accepted
- **Date:** 10 November 2026
- **Related:** [PRD BR-32, BR-33, AD-3](../PRD.md), [ADR-0004](0004-lazy-evaluation-of-time-based-rules.md), issue #55

## Context

BR-32 asks for an audit record of every change to a booking, waitlist entry, room, facility, opening hours, blackout or user: who made it (or "system"), what it was, when, and the values before and after. It must be written in the same transaction as the change, so a record never describes a change that was rolled back, and a change never commits without its record.

There are three ways to do this:

1. **Explicit calls.** Each service captures the values before a change and writes a record after it.
2. **Hibernate event listeners.** A listener sees every insert, update and delete Hibernate flushes, with the old and new values, and writes a record for each.
3. **Hibernate Envers.** A library that keeps a full copy of each audited table, one row per version.

Two facts shape the choice. Some changes are not made through Hibernate entities: the settle step records no-shows with one `UPDATE` statement, and the sweep expires waitlist entries the same way (ADR-0004). Those never reach a listener or Envers. Also, "who" is not always the signed-in user. When Alice cancels and Bob is promoted from the waitlist in the same request, AD-3 asks for the promotion to show as done by the system, not by Alice. A listener only knows whose request it is running in.

## Decision

1. Services record changes explicitly through `AuditLog`, passing the action (for example `BOOKING_CANCELLED`), the actor (`null` for the system), and the values before and after.
2. Each audited entity has an `auditState()` method listing the values worth keeping. Secrets and personal details are left out: no check-in token, password hash or email.
3. Booking and waitlist changes go through `BookingRecorder`, which applies the change, records it and queues its email together. A change that alters nothing, such as checking in twice, is not recorded.
4. Bulk updates return the IDs of the rows they changed (`UPDATE ... RETURNING`), and one record is written for each. Their records hold only the status, since the rows are not loaded.
5. `AuditLog` requires an open transaction (`Propagation.MANDATORY`) and writes with the same connection, so the record is committed or rolled back with the change.
6. The table refuses updates with a trigger (BR-33). Only the sweep deletes rows, after 180 days.

## Consequences

- The audit log says what happened in the project's own words (`WAITLIST_PROMOTED`, `BOOKING_NO_SHOW`), not just "row updated", and system actions are marked correctly.
- A new kind of change needs a matching audit call; nothing records it automatically. The integration tests check each kind of change, and code review should look for the call when a new write is added.
- Services that change bookings take a `BookingRecorder` instead of the email notifier, which kept their constructors within the Checkstyle limit of seven parameters. `SeriesService`'s rule checks moved into `SeriesChecks` for the same reason.
- `aRecordIsWrittenInTheChangesTransactionAndRolledBackWithIt` proves the record shares the change's transaction: written inside a transaction that is rolled back, it disappears.

## Rejected options

- **Hibernate event listeners:** miss the bulk `UPDATE`s, and cannot tell a promotion by the system apart from the request it happened in without extra context passed alongside, which is the explicit call again.
- **Envers:** stores whole copies of every table, which is more storage than the free database tier needs to spend ([COSTS.md](../COSTS.md)). It also misses bulk updates, and its history is harder to search by actor across entities.
