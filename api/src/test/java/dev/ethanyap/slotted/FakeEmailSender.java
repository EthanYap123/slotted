package dev.ethanyap.slotted;

import static org.assertj.core.api.Assertions.fail;

import dev.ethanyap.slotted.notification.application.EmailSender;
import dev.ethanyap.slotted.notification.domain.EmailContent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Stands in for Brevo in integration tests: records every email it is given. Tests share it, so
 * they look up emails by their own unique addresses.
 */
public class FakeEmailSender implements EmailSender {

  private final List<EmailContent> sent = new CopyOnWriteArrayList<>();
  private volatile Function<EmailContent, Result> behaviour = email -> new Result.Sent();

  @Override
  public Result send(EmailContent email) {
    var result = behaviour.apply(email);
    if (result instanceof Result.Sent) {
      sent.add(email);
    }
    return result;
  }

  /** Answers every send with this result from now on, until {@link #succeed()}. */
  public void answer(Result result) {
    behaviour = email -> result;
  }

  public void succeed() {
    behaviour = email -> new Result.Sent();
  }

  /** Emails accepted for this address so far. */
  public List<EmailContent> to(String address) {
    return sent.stream().filter(e -> e.toAddress().equalsIgnoreCase(address)).toList();
  }

  /** Waits briefly for an email of this kind, as emails are sent in the background after commit. */
  public EmailContent await(String address, String kind) {
    var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      var found = to(address).stream().filter(e -> e.kind().equals(kind)).findFirst();
      if (found.isPresent()) {
        return found.get();
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return fail("No " + kind + " email to " + address + "; got " + to(address));
  }
}
