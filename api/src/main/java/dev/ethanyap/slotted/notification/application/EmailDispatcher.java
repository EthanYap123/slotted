package dev.ethanyap.slotted.notification.application;

import dev.ethanyap.slotted.notification.application.EmailSender.Result;
import dev.ethanyap.slotted.notification.domain.DeliveryRules;
import dev.ethanyap.slotted.notification.domain.EmailStatus;
import dev.ethanyap.slotted.notification.infrastructure.EmailProperties;
import dev.ethanyap.slotted.notification.infrastructure.OutboxRepository;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends outbox emails, one at a time, each in a short transaction that holds that email's row lock
 * while it is sent, so two senders can never send the same email. Stops for the day at the daily
 * cap and leaves the rest queued (docs/COSTS.md).
 */
@Component
public class EmailDispatcher {

  private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

  private final OutboxRepository outbox;
  private final EmailSender sender;
  private final EmailProperties properties;
  private final TransactionTemplate transactions;
  private final Clock clock;

  EmailDispatcher(
      OutboxRepository outbox,
      EmailSender sender,
      EmailProperties properties,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.outbox = outbox;
    this.sender = sender;
    this.properties = properties;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** Sends emails that are due, up to {@code max}; returns what happened to them. */
  public Delivered sendDue(int max) {
    var sent = 0;
    var failed = 0;
    for (var id : outbox.findDueIds(clock.instant(), Limit.of(max))) {
      var outcome = sendIfDue(id);
      if (outcome.isEmpty()) {
        break;
      }
      if (outcome.get() == EmailStatus.SENT) {
        sent++;
      } else if (outcome.get() == EmailStatus.FAILED) {
        failed++;
      }
    }
    return new Delivered(sent, failed);
  }

  /**
   * Tries one email if it is due and nobody else is sending it. Returns its status afterwards, or
   * empty if the daily cap stopped it.
   */
  public Optional<EmailStatus> sendIfDue(long id) {
    try {
      return transactions.execute(status -> attempt(id));
    } catch (RuntimeException e) {
      // The email stays queued; the sweep tries it again.
      log.warn("Could not send outbox email {}", id, e);
      return Optional.of(EmailStatus.PENDING);
    }
  }

  private Optional<EmailStatus> attempt(long id) {
    var found = outbox.tryLockById(id);
    var now = clock.instant();
    if (found.isEmpty() || !found.get().isDue(now)) {
      return Optional.of(found.map(e -> e.getStatus()).orElse(EmailStatus.PENDING));
    }
    var email = found.get();
    if (DeliveryRules.isSkipped(email.getToAddress(), properties.skipDomains())) {
      email.markSkipped();
      return Optional.of(email.getStatus());
    }
    var startOfDay = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC);
    if (outbox.countSentSince(startOfDay.toInstant()) >= properties.dailyCap()) {
      return Optional.empty();
    }
    switch (sender.send(email.content())) {
      case Result.Sent sent -> email.markSent(now);
      case Result.TryAgain retry -> email.markFailed(retry.reason(), false, now);
      case Result.Refused refused -> email.markFailed(refused.reason(), true, now);
    }
    if (email.getStatus() != EmailStatus.SENT) {
      log.warn("Outbox email {} not sent (attempt {})", id, email.getAttempts());
    }
    return Optional.of(email.getStatus());
  }

  /** Deletes emails that are finished with; returns how many. */
  public int purgeFinished() {
    var cutoff = clock.instant().minus(properties.retention());
    return transactions.execute(status -> outbox.deleteFinishedBefore(cutoff));
  }

  /** How many emails a run sent, and how many it gave up on. */
  public record Delivered(int sent, int failed) {}
}
