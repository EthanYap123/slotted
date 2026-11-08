package dev.ethanyap.slotted.auth.application;

import dev.ethanyap.slotted.auth.infrastructure.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes refresh tokens that expired a while ago. An expired token is refused whether or not its
 * row exists, so this only keeps the table small (ADR-0004).
 */
@Component
public class ExpiredRefreshTokens {

  private final RefreshTokenRepository tokens;
  private final Clock clock;

  ExpiredRefreshTokens(RefreshTokenRepository tokens, Clock clock) {
    this.tokens = tokens;
    this.clock = clock;
  }

  /** Deletes tokens that expired longer ago than {@code retention}; returns how many. */
  @Transactional
  public int purge(Duration retention) {
    return tokens.deleteExpiredBefore(clock.instant().minus(retention));
  }
}
