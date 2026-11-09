package dev.ethanyap.slotted.notification.application;

import dev.ethanyap.slotted.notification.domain.EmailContent;
import dev.ethanyap.slotted.notification.domain.OutboxEmail;
import dev.ethanyap.slotted.notification.infrastructure.OutboxRepository;
import java.time.Clock;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Where other features hand over emails (ADR-0004). An email is written in the caller's
 * transaction, so it exists exactly when the change it reports was committed. After commit it is
 * sent in the background; if that fails, or the server stops first, the sweep tries again later.
 * Either way the caller's request never waits for, or fails because of, the email provider.
 */
@Component
public class EmailOutbox {

  private final OutboxRepository outbox;
  private final EmailDispatcher dispatcher;
  private final TaskExecutor executor;
  private final Clock clock;

  EmailOutbox(
      OutboxRepository outbox, EmailDispatcher dispatcher, TaskExecutor executor, Clock clock) {
    this.outbox = outbox;
    this.dispatcher = dispatcher;
    this.executor = executor;
    this.clock = clock;
  }

  /** Queues an email; one with a {@code dedupeKey} already used is ignored. */
  public void queue(EmailContent email) {
    Long id;
    if (email.dedupeKey() == null) {
      id = outbox.save(new OutboxEmail(email, clock.instant())).getId();
    } else {
      var inserted = outbox.insertUnlessQueued(email, clock.instant());
      if (inserted.isEmpty()) {
        return;
      }
      id = inserted.getFirst();
    }
    Runnable send = () -> dispatcher.sendIfDue(id);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              executor.execute(send);
            }
          });
    } else {
      executor.execute(send);
    }
  }
}
