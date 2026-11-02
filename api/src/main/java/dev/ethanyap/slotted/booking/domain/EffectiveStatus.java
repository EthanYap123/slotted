package dev.ethanyap.slotted.booking.domain;

/**
 * Status at a given moment (ADR-0004). A confirmed booking not checked in by its grace period is a
 * no-show, and an active booking whose end has passed is completed, even before anything has been
 * written to the database.
 */
public enum EffectiveStatus {
  CONFIRMED,
  CHECKED_IN,
  COMPLETED,
  CANCELLED,
  NO_SHOW;

  public boolean blocksRoom() {
    return this == CONFIRMED || this == CHECKED_IN;
  }
}
