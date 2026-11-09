package dev.ethanyap.slotted.notification.domain;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** When to try sending again, and which addresses are never sent to (docs/COSTS.md). */
public final class DeliveryRules {

  /** Tries before an email is given up on. */
  public static final int MAX_ATTEMPTS = 5;

  /** Waits after the 1st, 2nd, 3rd and 4th failed try. */
  private static final List<Duration> RETRY_DELAYS =
      List.of(
          Duration.ofMinutes(1),
          Duration.ofMinutes(5),
          Duration.ofMinutes(30),
          Duration.ofHours(2));

  private DeliveryRules() {}

  /** How long to wait after this many failed tries. */
  public static Duration retryDelay(int attemptsSoFar) {
    var index = Math.max(0, Math.min(attemptsSoFar, RETRY_DELAYS.size()) - 1);
    return RETRY_DELAYS.get(index);
  }

  /**
   * Whether the address is on a reserved domain (or one of its subdomains) that is never sent to,
   * such as the demo accounts' {@code example.com}. Entries may be top-level names like {@code
   * test}.
   */
  public static boolean isSkipped(String address, Collection<String> skipDomains) {
    var at = address == null ? -1 : address.lastIndexOf('@');
    if (at < 0) {
      return true;
    }
    var domain = address.substring(at + 1).strip().toLowerCase(Locale.ROOT);
    return skipDomains.stream()
        .map(d -> d.strip().toLowerCase(Locale.ROOT))
        .filter(d -> !d.isEmpty())
        .anyMatch(d -> domain.equals(d) || domain.endsWith("." + d));
  }
}
