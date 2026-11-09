package dev.ethanyap.slotted.notification.infrastructure;

import dev.ethanyap.slotted.notification.application.EmailSender;
import dev.ethanyap.slotted.notification.domain.EmailContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used when no Brevo key is set, for example on a laptop: writes a line to the log instead of
 * sending. The address is partly hidden, so logs never hold someone's full email.
 */
public class LoggingEmailSender implements EmailSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

  @Override
  public Result send(EmailContent email) {
    log.info("Email not sent (no BREVO_API_KEY): {} to {}", email.kind(), mask(email.toAddress()));
    return new Result.Sent();
  }

  /** {@code jane.doe@example.com} → {@code j***@example.com}. */
  static String mask(String address) {
    var at = address.indexOf('@');
    return at <= 0 ? "***" : address.charAt(0) + "***" + address.substring(at);
  }
}
