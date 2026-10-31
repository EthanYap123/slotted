package dev.ethanyap.slotted.auth.application;

import dev.ethanyap.slotted.auth.infrastructure.RefreshTokenRepository;
import dev.ethanyap.slotted.user.domain.UserAccessChanged;
import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Revokes every refresh token of a user whose role or status changed, in the same transaction as
 * the change. Their current access token expires within 15 minutes; admin actions also re-check the
 * database (ADR-0002).
 */
@Component
public class EndSessionsOnAccessChange {

  private final RefreshTokenRepository refreshTokens;
  private final Clock clock;

  public EndSessionsOnAccessChange(RefreshTokenRepository refreshTokens, Clock clock) {
    this.refreshTokens = refreshTokens;
    this.clock = clock;
  }

  @EventListener
  public void on(UserAccessChanged event) {
    refreshTokens.revokeAllForUser(event.userId(), clock.instant());
  }
}
