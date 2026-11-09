package dev.ethanyap.slotted.notification.application;

import dev.ethanyap.slotted.notification.domain.EmailContent;

/** Hands one email to a provider (docs/COSTS.md). Brevo in production; a log line without a key. */
public interface EmailSender {

  /** Never throws: every outcome is a {@link Result}. */
  Result send(EmailContent email);

  /** How a send went. */
  sealed interface Result {
    /** Accepted by the provider. */
    record Sent() implements Result {}

    /** Did not go through this time, but may on a later try (rate limit, outage, timeout). */
    record TryAgain(String reason) implements Result {}

    /** Refused for good, for example an invalid address; trying again would not help. */
    record Refused(String reason) implements Result {}
  }
}
