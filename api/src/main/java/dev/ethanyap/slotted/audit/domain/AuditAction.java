package dev.ethanyap.slotted.audit.domain;

/** What happened, and to which kind of thing. Stored by name, so names must not change. */
public enum AuditAction {
  BOOKING_CREATED(AuditEntity.BOOKING),
  /** A waiting student got the slot; always done by the system (BR-26). */
  BOOKING_PROMOTED(AuditEntity.BOOKING),
  BOOKING_CANCELLED(AuditEntity.BOOKING),
  BOOKING_CHECKED_IN(AuditEntity.BOOKING),
  BOOKING_ENDED_EARLY(AuditEntity.BOOKING),
  /** The settle step wrote down a no-show that time had already made true (ADR-0004). */
  BOOKING_NO_SHOW(AuditEntity.BOOKING),

  WAITLIST_JOINED(AuditEntity.WAITLIST_ENTRY),
  WAITLIST_LEFT(AuditEntity.WAITLIST_ENTRY),
  WAITLIST_PROMOTED(AuditEntity.WAITLIST_ENTRY),
  WAITLIST_EXPIRED(AuditEntity.WAITLIST_ENTRY),
  WAITLIST_CANCELLED(AuditEntity.WAITLIST_ENTRY),

  ROOM_CREATED(AuditEntity.ROOM),
  ROOM_UPDATED(AuditEntity.ROOM),
  /** The QR code was replaced. The token is a secret, so only the fact is recorded. */
  ROOM_QR_ROTATED(AuditEntity.ROOM),

  FACILITY_CREATED(AuditEntity.FACILITY),
  FACILITY_UPDATED(AuditEntity.FACILITY),
  FACILITY_MANAGERS_CHANGED(AuditEntity.FACILITY),
  OPENING_HOURS_CHANGED(AuditEntity.OPENING_HOURS),

  BLACKOUT_CREATED(AuditEntity.BLACKOUT),
  BLACKOUT_DELETED(AuditEntity.BLACKOUT),

  USER_REGISTERED(AuditEntity.USER),
  USER_CREATED(AuditEntity.USER),
  USER_UPDATED(AuditEntity.USER);

  private final AuditEntity entity;

  AuditAction(AuditEntity entity) {
    this.entity = entity;
  }

  public AuditEntity entity() {
    return entity;
  }
}
