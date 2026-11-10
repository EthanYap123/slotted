package dev.ethanyap.slotted.audit.domain;

/** The kinds of things the audit log records changes to (PRD BR-32). */
public enum AuditEntity {
  BOOKING,
  WAITLIST_ENTRY,
  ROOM,
  FACILITY,
  OPENING_HOURS,
  BLACKOUT,
  USER
}
