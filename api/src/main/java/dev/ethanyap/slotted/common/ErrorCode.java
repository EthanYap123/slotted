package dev.ethanyap.slotted.common;

/**
 * Stable error codes returned in the {@code code} field of every problem response. Clients switch
 * on these, never on titles. Documented in docs/api/errors.md; keep both in step.
 */
public enum ErrorCode {
  VALIDATION_FAILED(400, "The request is not valid"),
  UNAUTHENTICATED(401, "Sign in to continue"),
  INVALID_CREDENTIALS(401, "Email or password is incorrect"),
  REFRESH_TOKEN_INVALID(401, "Your session has expired"),
  FORBIDDEN(403, "You are not allowed to do this"),
  NOT_FOUND(404, "Not found"),
  EMAIL_TAKEN(409, "An account with this email already exists"),
  NAME_TAKEN(409, "This name is already in use"),
  WEAK_PASSWORD(400, "Choose a stronger password"),
  VERSION_CONFLICT(412, "This was changed by someone else"),
  RATE_LIMITED(429, "Too many requests"),
  SLOT_TAKEN(409, "This slot was just taken"),
  NOT_ALIGNED(422, "Times must be on the hour or half hour"),
  BOOKING_TOO_SHORT(422, "Booking is too short"),
  BOOKING_TOO_LONG(422, "Booking is too long"),
  OUTSIDE_BOOKING_WINDOW(422, "Outside the booking window"),
  OUTSIDE_OPENING_HOURS(422, "Outside opening hours"),
  OVER_CAPACITY(422, "Too many people for this room"),
  ROOM_INACTIVE(422, "Room is not available"),
  QUOTA_UPCOMING_EXCEEDED(422, "Too many upcoming bookings"),
  QUOTA_DAILY_HOURS_EXCEEDED(422, "Too many hours booked that day"),
  STUDENT_OVERLAP(422, "You already have a booking at this time"),
  SUSPENDED(403, "Booking is suspended after repeated no-shows"),
  BLACKOUT(422, "Closed at this time"),
  BLACKOUT_ENDED(422, "This blackout has ended"),
  ALREADY_STARTED(422, "Booking has already started"),
  NOT_CANCELLABLE(422, "Booking cannot be cancelled"),
  REASON_REQUIRED(422, "A reason is required"),
  NOT_A_MANAGER(422, "Only managers can manage a facility"),
  LAST_ADMIN(422, "You cannot remove your own admin access"),
  INTERNAL_ERROR(500, "Something went wrong");

  private final int status;
  private final String title;

  ErrorCode(int status, String title) {
    this.status = status;
    this.title = title;
  }

  public int status() {
    return status;
  }

  public String title() {
    return title;
  }
}
