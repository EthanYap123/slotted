package dev.ethanyap.slotted.maintenance.application;

import dev.ethanyap.slotted.auth.application.ExpiredRefreshTokens;
import dev.ethanyap.slotted.booking.application.WaitlistQueue;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.maintenance.infrastructure.MaintenanceProperties;
import dev.ethanyap.slotted.notification.application.EmailDispatcher;
import dev.ethanyap.slotted.room.infrastructure.RoomRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The scheduled cleanup (PRD SY-1, ADR-0004). It only writes down what is already true, so the
 * application stays correct whether or not it runs: overdue no-shows are recorded and their time
 * offered to the waitlist, started waitlist entries are marked expired, queued emails that are due
 * are sent, and old refresh tokens and sent emails are deleted. Running it twice in a row does
 * nothing the second time.
 */
@Service
public class SweepService {

  /** Arbitrary, fixed key for the Postgres advisory lock that stops two sweeps overlapping. */
  static final long LOCK_KEY = 5_310_771_053L;

  /** Bounds one sweep's work; the daily cap stops sending earlier anyway. */
  static final int MAX_EMAILS_PER_SWEEP = 300;

  private static final Logger log = LoggerFactory.getLogger(SweepService.class);

  private final RoomRepository rooms;
  private final WaitlistQueue queue;
  private final ExpiredRefreshTokens refreshTokens;
  private final EmailDispatcher emails;
  private final JdbcTemplate jdbc;
  private final MaintenanceProperties properties;
  private final Clock clock;
  private final AtomicReference<Instant> lastStarted = new AtomicReference<>(Instant.MIN);

  SweepService(
      RoomRepository rooms,
      WaitlistQueue queue,
      ExpiredRefreshTokens refreshTokens,
      EmailDispatcher emails,
      JdbcTemplate jdbc,
      MaintenanceProperties properties,
      Clock clock) {
    this.rooms = rooms;
    this.queue = queue;
    this.refreshTokens = refreshTokens;
    this.emails = emails;
    this.jdbc = jdbc;
    this.properties = properties;
    this.clock = clock;
  }

  /** Runs the sweep if the key is right, not too soon after the last one, and none is running. */
  public SweepResult sweep(String key) {
    if (properties.key().isEmpty() || !matches(key)) {
      throw new ApiException(ErrorCode.UNAUTHENTICATED, "The maintenance key is missing or wrong.");
    }
    var started = clock.instant();
    var previous = lastStarted.get();
    if (started.isBefore(previous.plus(properties.minInterval()))
        || !lastStarted.compareAndSet(previous, started)) {
      throw new ApiException(
          ErrorCode.RATE_LIMITED, "A sweep ran moments ago. Try again in a minute.");
    }
    var result = jdbc.execute((ConnectionCallback<SweepResult>) c -> runLocked(c, started));
    log.info("Maintenance sweep: {}", result);
    return result;
  }

  /**
   * Holds a session advisory lock on its own connection while the work runs in short transactions
   * of its own, so a sweep started elsewhere skips instead of doubling up.
   */
  private SweepResult runLocked(java.sql.Connection connection, Instant started)
      throws java.sql.SQLException {
    try (var lock = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
      lock.setLong(1, LOCK_KEY);
      try (var rows = lock.executeQuery()) {
        if (!rows.next() || !rows.getBoolean(1)) {
          return SweepResult.skipped();
        }
      }
    }
    try {
      return run(started);
    } finally {
      try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
        unlock.setLong(1, LOCK_KEY);
        unlock.execute();
      }
    }
  }

  private SweepResult run(Instant started) {
    var noShows = 0;
    var promoted = 0;
    for (var roomId : rooms.findAllIds()) {
      var settled = queue.settleAndPromote(roomId);
      noShows += settled.noShows();
      promoted += settled.promoted();
    }
    var expired = queue.expireStarted();
    var delivered = emails.sendDue(MAX_EMAILS_PER_SWEEP);
    var purged = refreshTokens.purge(properties.refreshTokenRetention()) + emails.purgeFinished();
    return new SweepResult(
        true,
        noShows,
        promoted,
        expired,
        delivered.sent(),
        delivered.failed(),
        purged,
        Duration.between(started, clock.instant()).toMillis());
  }

  /** Compares in constant time, so the response time says nothing about how much was right. */
  private boolean matches(String given) {
    return given != null
        && MessageDigest.isEqual(
            given.getBytes(StandardCharsets.UTF_8),
            properties.key().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * What a sweep did.
   *
   * @param ranSweep false if another sweep held the lock
   */
  public record SweepResult(
      boolean ranSweep,
      int noShowsSettled,
      int waitlistPromoted,
      int waitlistExpired,
      int emailsSent,
      int emailsFailed,
      int rowsPurged,
      long durationMs) {

    static SweepResult skipped() {
      return new SweepResult(false, 0, 0, 0, 0, 0, 0, 0);
    }
  }
}
