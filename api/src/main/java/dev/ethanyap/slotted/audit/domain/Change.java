package dev.ethanyap.slotted.audit.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One change for the audit log: who did what to which thing, and its values before and after (PRD
 * BR-32). A thing that was created has no {@code before}; one that was removed has no {@code
 * after}.
 *
 * @param actorId who made the change; null for the system
 * @param facilityId the facility the thing belongs to; null for users
 */
public record Change(
    AuditAction action,
    UUID entityId,
    UUID facilityId,
    UUID actorId,
    Map<String, ?> before,
    Map<String, ?> after) {

  public Change {
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(entityId, "entityId");
    if (before == null && after == null) {
      throw new IllegalArgumentException("A change needs a before or an after value");
    }
  }

  /** Whether anything is different. Checking in twice, for example, changes nothing. */
  public boolean changesSomething() {
    return !Objects.equals(before, after);
  }
}
