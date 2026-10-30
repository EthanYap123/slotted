# Slotted: product requirements

| | |
| --- | --- |
| Status | Draft for Phase 0 review |
| Owner | Ethan Yap |
| Last updated | 29 October 2026 |

## 1. Problem

Shared spaces on a campus (study rooms, sports courts, meeting rooms) are usually booked through spreadsheets, chat groups or first-come sign-up sheets. That leads to double bookings, rooms held by people who never turn up, and no data on which spaces are actually used.

Slotted is a booking system that makes a confirmed booking a guarantee: two people can never hold the same room at the same time, no-shows release their slot automatically, and people who missed out are offered freed slots through a waitlist.

## 2. Goals and success measures

| Goal | Measure |
| --- | --- |
| No double bookings, ever | Zero overlapping active bookings for a room, enforced by the database and proven by a concurrency test |
| Fewer wasted slots | Every booking not checked in 15 minutes after its start is released, whether or not the server was awake at that moment |
| Freed slots get reused | Cancellations promote the first eligible waitlisted student in the same transaction |
| Managers see real usage | Utilisation by room and hour of day for any date range |
| Runs for free | Every service on a free tier ([COSTS.md](COSTS.md)) |

## 3. Roles

| Role | Who | Can do |
| --- | --- | --- |
| **Student** | Anyone who registers | Browse rooms and availability, book, cancel own bookings, join and leave waitlists, check in with a QR code, see own bookings |
| **Manager** | Staff assigned to one or more facilities by an admin | Everything a student can, plus manage rooms, opening hours and blackout dates **in their facilities**, see and cancel any booking in their facilities, rotate a room's check-in QR code |
| **Admin** | System administrators | Everything, in every facility, plus create facilities, assign managers, change user roles, disable accounts, view the audit log and analytics |

A user has exactly one role. New registrations are always students; only an admin can change a role.

## 4. Key terms

- **Facility**: a building or venue with its own opening hours, for example "Library Level 3".
- **Room**: a bookable space in a facility, of type study room, sports court or meeting room, with a capacity.
- **Slot**: a half-open time range `[start, end)` in a room. Back-to-back bookings (10:00-11:00 and 11:00-12:00) do not overlap.
- **Active booking**: a booking with status `CONFIRMED` or `CHECKED_IN`. Only active bookings block a room.
- **Blackout**: a period when a room or a whole facility cannot be booked (maintenance, exams, events).
- **SGT**: Singapore time (Asia/Singapore, UTC+8). Every date and time a user sees or enters is SGT ([ADR-0003](adr/0003-timezone-handling.md)).

## 5. Business rules

Values marked *(config)* are application settings with the default shown, so they can change without code changes.

### 5.1 Making a booking

| ID | Rule |
| --- | --- |
| BR-1 | Start and end times are on 30-minute boundaries (`:00` or `:30` SGT). |
| BR-2 | Minimum length 30 minutes. Maximum length is the room's `maxBookingMinutes` (default 120, at most 240). |
| BR-3 | **Booking window:** a booking must start in the future and no more than 14 days ahead *(config)*. Recurring series have their own limit (BR-20). |
| BR-4 | The whole booking must fall inside the facility's opening hours for that day, and a booking cannot cross midnight. |
| BR-5 | The booking must not overlap a blackout for the room or its facility. |
| BR-6 | The booking must not overlap another active booking for the same room. This is enforced by a database constraint, not only by application checks ([ADR-0001](adr/0001-double-booking-prevention.md)). |
| BR-7 | Attendee count is at least 1 and at most the room's capacity. |
| BR-8 | **Student quotas:** at most 3 upcoming active bookings *(config)*, and at most 4 booked hours per SGT calendar day across all rooms *(config)*. Managers and admins are not limited. |
| BR-9 | A student cannot hold two active bookings that overlap in time, even in different rooms. |
| BR-10 | Rooms that are inactive cannot be booked. Existing bookings stay until they are cancelled. |
| BR-11 | A student who is suspended (BR-17) cannot make new bookings or join waitlists. |

### 5.2 Cancelling

| ID | Rule |
| --- | --- |
| BR-12 | A student can cancel their own `CONFIRMED` booking at any time before it starts. |
| BR-13 | Cancelling less than 2 hours before the start *(config)* is recorded as a **late cancellation**. It is shown in the student's history and in analytics but has no penalty. |
| BR-14 | A manager (in their facilities) or an admin can cancel any active booking, including one already started, with a mandatory reason. The student is emailed. |
| BR-15 | A checked-in booking can be ended early by its owner. The rest of the slot is released from the next 30-minute boundary. |

### 5.3 Check-in and no-shows

| ID | Rule |
| --- | --- |
| BR-16 | **Check-in window:** from 10 minutes before start to 15 minutes after start. Check-in is by scanning the room's QR code while signed in. The QR code holds a per-room secret token, so check-in requires being at the room. |
| BR-17 | **No-show:** a `CONFIRMED` booking not checked in by start + 15 minutes becomes `NO_SHOW` and stops blocking the room from that moment. This must be true for every read and write at that moment, even if no background job has run ([ADR-0004](adr/0004-lazy-evaluation-of-time-based-rules.md)). |
| BR-18 | **No-show penalty:** 3 no-shows within 30 days *(config)* suspends the student from booking for 7 days *(config)*. The student is emailed when suspended. An admin can lift a suspension. |
| BR-19 | Booking reminders before the start time are not sent (see [Out of scope](#9-out-of-scope)). |

### 5.4 Recurring bookings

| ID | Rule |
| --- | --- |
| BR-20 | A series repeats **weekly** (all roles) or **daily** (managers and admins), with 2 to 8 occurrences *(config)*. The last occurrence must start within 8 weeks *(config)*; the 14-day window (BR-3) does not apply to series. |
| BR-21 | Every occurrence must pass BR-1, BR-2, BR-4 to BR-7, BR-9 and BR-10 on its own. For students, each occurrence counts toward the daily-hours quota but the series as a whole counts as **one** booking toward the upcoming-bookings quota. A student can have at most 1 active series *(config)*. |
| BR-22 | **Partial conflicts:** before saving, the user gets a preview listing each occurrence as bookable or not, with the reason. They choose **book the available ones** (conflicting dates are skipped and listed) or **cancel**. A series where no occurrence is bookable is rejected. |
| BR-23 | Occurrences can be cancelled one at a time, or the whole series from a date onwards. |

### 5.5 Waitlist

| ID | Rule |
| --- | --- |
| BR-24 | A student can join the waitlist for an exact slot (room, start, end) that is fully or partly taken. The slot must itself pass the rules in 5.1 except BR-6. At most 3 waiting entries per student *(config)*. |
| BR-25 | Entries are first come, first served, ordered by the time they joined. |
| BR-26 | **Auto-promotion:** whenever a booking is cancelled, released as a no-show or ended early, the system finds waiting entries for that room whose slot is now completely free and has not started, and promotes them in queue order. Promotion creates a `CONFIRMED` booking for the student in the same transaction and emails them. |
| BR-27 | During promotion the student's quotas are checked again. If the student is no longer eligible (quota full, suspended, overlapping booking elsewhere), that entry is skipped and stays waiting, and the next one is tried. |
| BR-28 | An entry expires when its slot's start time passes. A promoted student who no longer wants the slot cancels it like any other booking, which promotes the next person. |

### 5.6 Blackouts and opening hours

| ID | Rule |
| --- | --- |
| BR-29 | Opening hours are set per facility for each day of the week as one open period (for example Mon 08:00-22:00), or closed. |
| BR-30 | A blackout covers a whole facility or one room, for a date-time range, with a reason. |
| BR-31 | Before creating a blackout or shortening opening hours, the manager sees how many active bookings would be affected. Confirming cancels those bookings with the blackout reason and emails the students. Waitlist entries inside the blackout are expired. |

### 5.7 Audit

| ID | Rule |
| --- | --- |
| BR-32 | Every change to a booking, waitlist entry, room, facility, opening hours, blackout or user role/status writes an audit record in the same transaction: who (or "system"), what, when, and the values before and after. |
| BR-33 | Audit records cannot be edited or deleted through the application. They are kept for 180 days *(config)*. |

## 6. User stories and acceptance criteria

Acceptance criteria use Given / When / Then. Story IDs are referenced from issues and tests.

### 6.1 Student

**ST-1 Register and sign in.** As a student, I want to create an account and sign in so that my bookings are mine.

- Given a new email and a password that meets the policy (at least 10 characters, not in a common-password list), when I register, then my account is created with role Student and I am signed in.
- Given an email that is already registered, when I register, then I see "An account with this email already exists" and no account is created.
- Given wrong credentials, when I sign in, then I see one generic error that does not say whether the email exists.
- Given I am signed in and idle, when my access token expires, then the app refreshes it silently and I stay signed in for up to 7 days.

**ST-2 See availability.** As a student, I want to see when a room is free so that I can pick a time.

- Given a facility and a date within the booking window, when I open the timeline, then I see each room as a row of 30-minute cells marked free, booked, blackout or closed, in SGT.
- Given a booking that became a no-show 2 minutes ago and no background job has run, when I load the timeline, then the rest of that slot shows as free.
- Given I am on a phone, the timeline is usable without sideways page scrolling.

**ST-3 Book a slot.** As a student, I want to book a free slot so that the room is held for me.

- Given a free slot that passes all rules, when I book it, then the booking is `CONFIRMED`, it appears under My bookings, and a confirmation email is queued.
- Given a slot that breaks a rule, when I book it, then nothing is saved and I see which rule failed (for example "Bookings can be at most 2 hours long").
- Given another student books the same slot a moment before me, when my request reaches the server, then I get "This slot was just taken" and an offer to join the waitlist, and only one booking exists.
- Given I already have 3 upcoming bookings, when I try to book, then I am told the limit and nothing is saved.

**ST-4 Cancel a booking.** As a student, I want to cancel a booking I no longer need so that someone else can use the room.

- Given my confirmed booking that has not started, when I cancel, then its status is `CANCELLED`, the slot shows free (or goes to the next waitlisted student) and a cancellation email is queued.
- Given the start is less than 2 hours away, when I cancel, then it is recorded as a late cancellation and I was warned before confirming.
- Given a booking that has started, when I try to cancel, then I am told I can end it early instead (if checked in) and nothing changes.

**ST-5 My bookings.** As a student, I want one place to see my bookings so that I know where to be.

- Given I have bookings, when I open My bookings, then I see upcoming bookings first (soonest at the top) and past ones separately, each with room, facility, time in SGT and status.
- Given a booking whose check-in window is open, then the card shows a "Check in" action.

**ST-6 Recurring booking.** As a student, I want to book the same room every week so that my study group has a regular place.

- Given a weekly pattern of 6 occurrences where 2 dates clash with other bookings, when I preview, then I see 4 bookable and 2 not bookable, each with the reason.
- When I choose "book the available ones", then 4 bookings are created in one series and the response lists the 2 skipped dates.
- Given every occurrence clashes, when I try to save, then the series is rejected and nothing is saved.

**ST-7 Join a waitlist.** As a student, I want to wait for a taken slot so that I get it if it frees up.

- Given a taken slot, when I join its waitlist, then I see my position in the queue.
- Given I am first in the queue and the booking is cancelled, then I immediately hold a confirmed booking and get an email, without anyone running a job.
- Given I am first but I now have 3 upcoming bookings, when the slot frees, then the next eligible student gets it and my entry stays waiting.
- Given the slot's start time passes, then my entry shows as expired.

**ST-8 Check in by QR code.** As a student, I want to check in at the room so that my booking is not released.

- Given my booking starts in 5 minutes, when I scan the room's QR code while signed in, then the booking becomes `CHECKED_IN` and I see a confirmation.
- Given I scan more than 15 minutes after the start, then I am told the check-in window has closed and the booking was released.
- Given I scan the QR code of a different room, then I am told I have no booking in this room right now.
- Given I scan a QR code whose token was rotated, then check-in fails with "This QR code is no longer valid".

### 6.2 Manager

**MG-1 Manage rooms.** As a manager, I want to add and edit rooms in my facilities so that the list matches reality.

- Given a facility I manage, when I add a room with name, type, capacity and maximum booking length, then it appears in availability.
- Given a facility I do not manage, when I try to add or edit a room, then I get "403 Forbidden" and nothing changes.
- When I deactivate a room, then it can no longer be booked and existing bookings are kept.

**MG-2 Set opening hours.** As a manager, I want to set opening hours per weekday.

- Given I change Monday to 09:00-18:00 and 3 future bookings fall outside, then I see "3 bookings will be cancelled" before confirming, and after confirming they are cancelled with emails sent.

**MG-3 Add a blackout.** As a manager, I want to block a room or the whole facility for maintenance.

- Given a blackout from 12 Nov 08:00 to 14 Nov 22:00 on Room A, when I preview, then I see the affected bookings; when I confirm, then those bookings are cancelled, waitlist entries inside it expire, and the timeline shows the blackout.
- When I delete a future blackout, then those times become bookable again (cancelled bookings are not restored).

**MG-4 See and cancel bookings.** As a manager, I want to see all bookings in my facilities and cancel one when needed.

- Given a date range, when I open the bookings list, then I see every booking in my facilities with student name, status and check-in time.
- When I cancel a booking, then I must enter a reason and the student is emailed with it.

**MG-5 Print and rotate QR codes.** As a manager, I want a printable QR code for each room and to be able to invalidate it.

- When I open a room's QR page, then I get a printable code with the room name.
- When I rotate the code, then the old one stops working at once.

### 6.3 Admin

**AD-1 Manage users.** As an admin, I want to change roles and disable accounts.

- When I make a user a manager and assign facilities, then they can manage only those facilities.
- When I disable a user, then their sign-in and refresh tokens stop working at once and their future bookings are cancelled with an email.
- An admin cannot remove the admin role from themselves or disable themselves, so there is always at least one admin.

**AD-2 Manage facilities.** As an admin, I want to create facilities and assign managers to them.

**AD-3 Audit log.** As an admin, I want to see who changed what.

- Given a booking ID, when I filter the audit log by it, then I see every change to that booking in time order, including system actions (no-show release, waitlist promotion) marked as "system".

**AD-4 Analytics.** As an admin or manager, I want to see how rooms are used.

- Given a date range, when I open analytics, then I see per room: utilisation (booked hours ÷ open hours), check-in rate, no-show rate, cancellation and late-cancellation counts, and a heat map of utilisation by weekday and hour.
- A manager sees only their facilities.

### 6.4 System

**SY-1 Cleanup job.** As the system, I want a scheduled job to tidy up so that emails and records are not left waiting when nobody visits.

- Given the server was asleep, when the GitHub Actions schedule calls the maintenance endpoint with the right key, then overdue no-shows are recorded, waitlist entries are expired or promoted, suspensions are applied and queued emails are sent.
- Given a wrong or missing key, then the endpoint returns 401 and does nothing.
- Given the job runs twice at the same time or twice in a row, then the result is the same as running it once.

## 7. Non-functional requirements

| Area | Requirement |
| --- | --- |
| Correctness | No overlapping active bookings, under any concurrency, enforced by the database |
| Time rules | No-show release and waitlist expiry correct on every read, independent of background jobs |
| Performance | Availability for one facility and one day returns in under 300 ms when the API is warm. Cold start on the free host is about a minute; the web app shows a clear "waking the server" message. |
| Memory | API runs within 512 MB RAM |
| Security | Passwords hashed with BCrypt; role checks on every endpoint; rate-limited sign-in; no secrets in the repo; OWASP Top 10 reviewed in Phase 5 |
| Privacy | Students see only their own bookings; other people's bookings show as "Booked" without names. Managers see names only in their facilities. |
| Accessibility | WCAG 2.2 AA target: keyboard-usable timeline and dialogs, labelled controls, visible focus, colour not the only signal |
| Time zone | All times shown and entered in SGT, stored as UTC instants |
| Observability | Structured JSON logs with request IDs, Actuator health checks, Sentry errors |
| Cost | $0, see [COSTS.md](COSTS.md) |

## 8. Assumptions

- One institution, one time zone (SGT). Facilities have a time-zone field for the future, but only Asia/Singapore is accepted.
- Users have a smartphone that can scan QR codes and open a link.
- Email is best effort: a booking is valid whether or not its email was delivered.
- Demo data and accounts are public; no real student data is stored.

## 9. Out of scope

- Payments, deposits or fees for rooms.
- Single sign-on (school SSO, Google sign-in) and email verification of new accounts.
- Reminder emails or push notifications at a set time before a booking. A sleeping free-tier server cannot guarantee when they would go out ([ADR-0004](adr/0004-lazy-evaluation-of-time-based-rules.md)).
- SMS, push notifications and calendar sync (iCal feeds, Google Calendar).
- Multiple institutions (multi-tenancy) and multiple time zones.
- Booking equipment or resources other than rooms; splitting a room into sub-areas.
- Approval workflows (bookings are confirmed instantly).
- Native mobile apps (the web app is responsive instead).
- Choosing a place in the waitlist or swapping bookings between students.
- Partial-slot waitlists ("any 1 hour between 2 and 6 pm").
- Internationalisation; the interface is English only.
