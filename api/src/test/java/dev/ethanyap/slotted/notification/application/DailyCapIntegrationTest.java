package dev.ethanyap.slotted.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ethanyap.slotted.FakeEmailSender;
import dev.ethanyap.slotted.IntegrationTest;
import dev.ethanyap.slotted.notification.domain.EmailContent;
import dev.ethanyap.slotted.notification.domain.OutboxEmail;
import dev.ethanyap.slotted.notification.infrastructure.EmailProperties;
import dev.ethanyap.slotted.notification.infrastructure.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * docs/COSTS.md: sending stops at the daily cap and the rest stays queued. A dispatcher with a cap
 * of "what has been sent today, plus one" is built here, so the test does not depend on how many
 * emails other tests sent.
 */
@IntegrationTest
class DailyCapIntegrationTest {

  @Autowired OutboxRepository outbox;
  @Autowired FakeEmailSender sender;
  @Autowired PlatformTransactionManager transactions;
  @Autowired EmailProperties properties;
  @Autowired Clock clock;

  @Test
  void sendingStopsAtTheDailyCapAndTheRestWaits() {
    var startOfDay =
        clock.instant().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC);
    var sentToday = outbox.countSentSince(startOfDay.toInstant());
    var capped =
        new EmailDispatcher(
            outbox,
            sender,
            new EmailProperties(
                properties.fromAddress(),
                properties.fromName(),
                "",
                (int) sentToday + 1,
                List.of(),
                properties.webUrl(),
                Duration.ofDays(30)),
            transactions,
            clock);
    var first = queue();
    var second = queue();

    var delivered = capped.sendDue(10);

    assertThat(delivered.sent()).isEqualTo(1);
    assertThat(outbox.findById(first).orElseThrow().getStatus().name()).isEqualTo("SENT");
    var waiting = outbox.findById(second).orElseThrow();
    assertThat(waiting.getStatus().name()).isEqualTo("PENDING");
    assertThat(waiting.getAttempts()).isZero();
  }

  private long queue() {
    var address = "cap-" + UUID.randomUUID() + "@example.com";
    return outbox
        .saveAndFlush(
            new OutboxEmail(
                new EmailContent(address, null, "TEST", "Subject", "Text", "<p>Text</p>", null),
                clock.instant().minusSeconds(1)))
        .getId();
  }
}
