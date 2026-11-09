package dev.ethanyap.slotted.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ethanyap.slotted.IntegrationTest;
import dev.ethanyap.slotted.notification.domain.EmailContent;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two transactions queue the same deduplicated email at the same moment, for example two bookings
 * in a room both noticing one student's suspension. Both must commit, and only one email may exist.
 */
@IntegrationTest
class DedupeRaceIntegrationTest {

  private static final int RACERS = 4;

  @Autowired EmailOutbox outbox;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  @RepeatedTest(3)
  void racingTransactionsQueueOneEmailAndNoneFails() throws Exception {
    var key = "race:" + UUID.randomUUID();
    var email =
        new EmailContent(
            "race-" + UUID.randomUUID() + "@example.org", null, "TEST", "S", "T", "<p>T</p>", key);
    var template = new TransactionTemplate(transactions);
    var executor = Executors.newFixedThreadPool(RACERS);
    try {
      var ready = new CountDownLatch(RACERS);
      var go = new CountDownLatch(1);
      var results = new ArrayList<Future<?>>();
      for (var i = 0; i < RACERS; i++) {
        results.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  template.executeWithoutResult(status -> outbox.queue(email));
                  return null;
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      for (var result : results) {
        result.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM email_outbox WHERE dedupe_key = ?", Integer.class, key))
        .isEqualTo(1);
  }
}
