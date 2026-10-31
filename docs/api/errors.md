# API error codes

Every error is an RFC 9457 problem (`application/problem+json`) with a stable `code`. Clients switch on `code`, never on `title` or `detail`, which are for people and may change. The full contract is in [openapi.yaml](openapi.yaml).

| Code | HTTP | Meaning | Rule |
| --- | --- | --- | --- |
| <a id="validation_failed"></a>`VALIDATION_FAILED` | 400 | Malformed body or invalid field; see `errors[]` | |
| <a id="unauthenticated"></a>`UNAUTHENTICATED` | 401 | Missing or expired access token, or wrong maintenance key | |
| <a id="invalid_credentials"></a>`INVALID_CREDENTIALS` | 401 | Email or password wrong (never says which) | ST-1 |
| <a id="refresh_token_invalid"></a>`REFRESH_TOKEN_INVALID` | 401 | Refresh token unknown, expired, revoked or reused | ADR-0002 |
| <a id="forbidden"></a>`FORBIDDEN` | 403 | Wrong role, or outside the manager's facilities | |
| <a id="not_found"></a>`NOT_FOUND` | 404 | Does not exist or not visible to the caller | |
| <a id="email_taken"></a>`EMAIL_TAKEN` | 409 | An account with this email exists | ST-1 |
| <a id="weak_password"></a>`WEAK_PASSWORD` | 400 | Password too short or too common | ST-1 |
| <a id="version_conflict"></a>`VERSION_CONFLICT` | 412 | Someone else changed this record since it was read | |
| <a id="rate_limited"></a>`RATE_LIMITED` | 429 | Too many requests; see `Retry-After` | |
| <a id="internal_error"></a>`INTERNAL_ERROR` | 500 | Unexpected server error; quote the `requestId` when reporting it | |
| <a id="slot_taken"></a>`SLOT_TAKEN` | 409 | Another active booking overlaps; `waitlistable` says if the caller can wait for it | BR-6 |
| <a id="not_aligned"></a>`NOT_ALIGNED` | 422 | Start or end not on a 30-minute boundary | BR-1 |
| <a id="booking_too_short"></a>`BOOKING_TOO_SHORT` | 422 | Shorter than 30 minutes | BR-2 |
| <a id="booking_too_long"></a>`BOOKING_TOO_LONG` | 422 | Longer than the room allows | BR-2 |
| <a id="outside_booking_window"></a>`OUTSIDE_BOOKING_WINDOW` | 422 | In the past or more than 14 days ahead | BR-3 |
| <a id="outside_opening_hours"></a>`OUTSIDE_OPENING_HOURS` | 422 | Not inside opening hours, or crosses midnight | BR-4 |
| <a id="blackout"></a>`BLACKOUT` | 422 | Overlaps a blackout | BR-5 |
| <a id="over_capacity"></a>`OVER_CAPACITY` | 422 | More attendees than the room holds | BR-7 |
| <a id="room_inactive"></a>`ROOM_INACTIVE` | 422 | Room is deactivated | BR-10 |
| <a id="quota_upcoming_exceeded"></a>`QUOTA_UPCOMING_EXCEEDED` | 422 | Student already has 3 upcoming bookings | BR-8 |
| <a id="quota_daily_hours_exceeded"></a>`QUOTA_DAILY_HOURS_EXCEEDED` | 422 | Would exceed 4 booked hours that day | BR-8 |
| <a id="student_overlap"></a>`STUDENT_OVERLAP` | 422 | Student already has a booking at that time in another room | BR-9 |
| <a id="suspended"></a>`SUSPENDED` | 403 | Student is suspended for no-shows | BR-11, BR-18 |
| <a id="already_started"></a>`ALREADY_STARTED` | 422 | Students cannot cancel a booking that has started | BR-12 |
| <a id="not_cancellable"></a>`NOT_CANCELLABLE` | 422 | Booking is already cancelled, a no-show or finished | |
| <a id="reason_required"></a>`REASON_REQUIRED` | 422 | Managers and admins must give a reason | BR-14 |
| <a id="not_checked_in"></a>`NOT_CHECKED_IN` | 422 | Only checked-in bookings can be ended early | BR-15 |
| <a id="check_in_not_open"></a>`CHECK_IN_NOT_OPEN` | 422 | More than 10 minutes before the start | BR-16 |
| <a id="check_in_closed"></a>`CHECK_IN_CLOSED` | 422 | More than 15 minutes after the start; the booking was released | BR-16, BR-17 |
| <a id="no_booking_to_check_in"></a>`NO_BOOKING_TO_CHECK_IN` | 422 | No booking for the caller in this room around now | BR-16 |
| <a id="qr_token_invalid"></a>`QR_TOKEN_INVALID` | 422 | QR token unknown or rotated | MG-5 |
| <a id="slot_available"></a>`SLOT_AVAILABLE` | 422 | Slot is free, so book it instead of waiting | BR-24 |
| <a id="already_waiting"></a>`ALREADY_WAITING` | 409 | Already on the waitlist for this slot | BR-24 |
| <a id="waitlist_limit"></a>`WAITLIST_LIMIT` | 422 | Already waiting for 3 slots | BR-24 |
| <a id="series_limit"></a>`SERIES_LIMIT` | 422 | Student already has an active series, or `DAILY` used by a student | BR-20, BR-21 |
| <a id="series_too_far"></a>`SERIES_TOO_FAR` | 422 | Last occurrence more than 8 weeks ahead | BR-20 |
| <a id="series_nothing_bookable"></a>`SERIES_NOTHING_BOOKABLE` | 422 | No occurrence can be booked | BR-22 |
| <a id="blackout_ended"></a>`BLACKOUT_ENDED` | 422 | Past blackouts cannot be removed | MG-3 |
| <a id="not_a_manager"></a>`NOT_A_MANAGER` | 422 | A user assigned to a facility must have the Manager role | AD-1 |
| <a id="last_admin"></a>`LAST_ADMIN` | 422 | An admin cannot demote or disable themselves | AD-1 |
