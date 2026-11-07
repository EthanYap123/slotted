package dev.ethanyap.slotted.booking.domain;

/** Where a waitlist entry stands. An entry is {@code EXPIRED} from the moment its slot starts. */
public enum WaitlistStatus {
  WAITING,
  PROMOTED,
  EXPIRED,
  CANCELLED
}
