package dev.ethanyap.slotted.booking.domain;

/** Status as stored. What users see is {@link EffectiveStatus}, which also depends on the time. */
public enum BookingStatus {
  CONFIRMED,
  CHECKED_IN,
  CANCELLED,
  NO_SHOW;

  /** Active bookings block the room; the exclusion constraint covers exactly these (ADR-0001). */
  public boolean isActive() {
    return this == CONFIRMED || this == CHECKED_IN;
  }
}
