package dev.ethanyap.slotted.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Retries and reserved domains (docs/COSTS.md). */
class DeliveryRulesTest {

  private static final List<String> SKIP = List.of("example.com", "test", " Invalid ");

  @ParameterizedTest(name = "{0} is skipped: {1}")
  @CsvSource({
    "jane@example.com, true",
    "jane@EXAMPLE.COM, true",
    "jane@mail.example.com, true",
    "jane@school.test, true",
    "jane@x.invalid, true",
    "jane@notexample.com, false",
    "jane@example.com.sg, false",
    "jane@gmail.com, false",
  })
  void reservedDomainsAndTheirSubdomainsAreSkipped(String address, boolean skipped) {
    assertThat(DeliveryRules.isSkipped(address, SKIP)).isEqualTo(skipped);
  }

  @Test
  void anAddressWithoutADomainIsNeverSent() {
    assertThat(DeliveryRules.isSkipped("nobody", SKIP)).isTrue();
    assertThat(DeliveryRules.isSkipped(null, SKIP)).isTrue();
  }

  @Test
  void waitsGrowAfterEachFailedTryAndThenStop() {
    assertThat(DeliveryRules.retryDelay(1)).isEqualTo(Duration.ofMinutes(1));
    assertThat(DeliveryRules.retryDelay(2)).isEqualTo(Duration.ofMinutes(5));
    assertThat(DeliveryRules.retryDelay(3)).isEqualTo(Duration.ofMinutes(30));
    assertThat(DeliveryRules.retryDelay(4)).isEqualTo(Duration.ofHours(2));
    assertThat(DeliveryRules.retryDelay(9)).isEqualTo(Duration.ofHours(2));
  }

  @Test
  void anEmailIsRetriedUntilItsFifthTryThenGivenUp() {
    var now = Instant.parse("2026-11-10T02:00:00Z");
    var email = new OutboxEmail(new EmailContent("a@b.co", null, "K", "S", "T", "H", null), now);

    for (var tryNumber = 1; tryNumber <= 4; tryNumber++) {
      email.markFailed("timeout", false, now);
      assertThat(email.getStatus()).isEqualTo(EmailStatus.PENDING);
      assertThat(email.isDue(now)).isFalse();
    }
    email.markFailed("timeout", false, now);

    assertThat(email.getStatus()).isEqualTo(EmailStatus.FAILED);
    assertThat(email.getAttempts()).isEqualTo(5);
  }

  @Test
  void aRefusalIsFinalAndLongErrorsAreShortened() {
    var now = Instant.parse("2026-11-10T02:00:00Z");
    var email = new OutboxEmail(new EmailContent("a@b.co", null, "K", "S", "T", "H", null), now);

    email.markFailed("x".repeat(900), true, now);

    assertThat(email.getStatus()).isEqualTo(EmailStatus.FAILED);
    assertThat(email.getAttempts()).isEqualTo(1);
  }
}
