package dev.ethanyap.slotted.booking.domain;

/** How a booking was made. Series and waitlist bookings arrive in Phase 3. */
public enum BookingSource {
  DIRECT,
  SERIES,
  WAITLIST
}
