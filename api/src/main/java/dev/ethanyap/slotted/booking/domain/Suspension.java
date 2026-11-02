package dev.ethanyap.slotted.booking.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * No-show suspensions (BR-18), derived rather than stored (ADR-0004): reaching the limit of
 * no-shows within the period suspends a student until the latest of them plus the suspension
 * length. No-shows before an admin lifted a suspension do not count.
 */
public final class Suspension {

  private Suspension() {}

  /**
   * @param noShowTimes when each of the student's no-shows was released (start plus grace)
   * @param clearedAt when an admin last lifted a suspension, or null
   */
  public static Optional<Instant> until(
      Collection<Instant> noShowTimes, Instant clearedAt, Instant now, BookingPolicy policy) {
    var periodStart = now.minus(policy.noShowPeriod());
    var counted =
        noShowTimes.stream()
            .filter(t -> t.isAfter(periodStart) && !t.isAfter(now))
            .filter(t -> clearedAt == null || t.isAfter(clearedAt))
            .sorted()
            .toList();
    if (counted.size() < policy.noShowLimit()) {
      return Optional.empty();
    }
    var until = counted.getLast().plus(policy.suspension());
    return now.isBefore(until) ? Optional.of(until) : Optional.empty();
  }
}
