package dev.ethanyap.slotted.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.maintenance.application.SweepService.SweepResult;
import dev.ethanyap.slotted.maintenance.infrastructure.MaintenanceProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** The sweep's key and its minimum interval, without a database. */
class SweepServiceTest {

  private static final String KEY = "a-maintenance-key-that-is-long-enough-1234";
  private static final Instant NOW = Instant.parse("2026-11-08T03:17:00Z");

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private MutableClock clock;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(NOW);
    when(jdbc.execute(any(ConnectionCallback.class)))
        .thenReturn(new SweepResult(true, 0, 0, 0, 0, 0, 0, 5));
  }

  private SweepService service(String key) {
    return new SweepService(
        null,
        null,
        null,
        jdbc,
        new MaintenanceProperties(key, Duration.ofMinutes(1), Duration.ofDays(1)),
        clock);
  }

  @Test
  void theRightKeyRunsTheSweep() {
    assertThat(service(KEY).sweep(KEY).ranSweep()).isTrue();
  }

  @Test
  void aMissingOrWrongKeyIsRefusedWithoutTouchingTheDatabase() {
    var sweeps = service(KEY);

    assertCode(() -> sweeps.sweep(null), ErrorCode.UNAUTHENTICATED);
    assertCode(() -> sweeps.sweep(KEY + "x"), ErrorCode.UNAUTHENTICATED);
    assertCode(() -> sweeps.sweep(KEY.substring(1)), ErrorCode.UNAUTHENTICATED);
    verify(jdbc, never()).execute(any(ConnectionCallback.class));
  }

  @Test
  void withNoKeyConfiguredTheEndpointIsOff() {
    assertCode(() -> service("").sweep(""), ErrorCode.UNAUTHENTICATED);
    verify(jdbc, never()).execute(any(ConnectionCallback.class));
  }

  @Test
  void aSecondCallWithinAMinuteIsRateLimited() {
    var sweeps = service(KEY);
    sweeps.sweep(KEY);

    clock.advance(Duration.ofSeconds(59));
    assertCode(() -> sweeps.sweep(KEY), ErrorCode.RATE_LIMITED);

    clock.advance(Duration.ofSeconds(1));
    assertThat(sweeps.sweep(KEY).ranSweep()).isTrue();
    verify(jdbc, times(2)).execute(any(ConnectionCallback.class));
  }

  @Test
  void aShortKeyIsRefusedAtStartup() {
    assertThatThrownBy(() -> service("too-short"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 32");
  }

  private static void assertCode(Runnable action, ErrorCode code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  /** A clock the test moves by hand. */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }
}
