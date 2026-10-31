package dev.ethanyap.slotted.auth.application;

import dev.ethanyap.slotted.auth.domain.PasswordPolicy;
import dev.ethanyap.slotted.auth.domain.RefreshToken;
import dev.ethanyap.slotted.auth.infrastructure.RefreshTokenRepository;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.user.domain.User;
import dev.ethanyap.slotted.user.infrastructure.UserRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration, sign-in, token refresh and sign-out (PRD ST-1, ADR-0002). */
@Service
public class AuthService {

  private final UserRepository users;
  private final RefreshTokenRepository refreshTokens;
  private final PasswordEncoder passwordEncoder;
  private final PasswordPolicy passwordPolicy;
  private final TokenService tokens;
  private final Clock clock;

  /** Compared against when the email is unknown, so both failures take the same time. */
  private final String timingDummyHash;

  public AuthService(
      UserRepository users,
      RefreshTokenRepository refreshTokens,
      PasswordEncoder passwordEncoder,
      PasswordPolicy passwordPolicy,
      TokenService tokens,
      Clock clock) {
    this.users = users;
    this.refreshTokens = refreshTokens;
    this.passwordEncoder = passwordEncoder;
    this.passwordPolicy = passwordPolicy;
    this.tokens = tokens;
    this.clock = clock;
    this.timingDummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  @Transactional
  public SignedIn register(String email, String password, String fullName) {
    passwordPolicy
        .problemWith(password)
        .ifPresent(
            problem -> {
              throw new ApiException(ErrorCode.WEAK_PASSWORD, problem);
            });
    var normalized = User.normalizeEmail(email);
    if (users.existsByEmail(normalized)) {
      throw new ApiException(ErrorCode.EMAIL_TAKEN);
    }
    User user;
    try {
      user =
          users.saveAndFlush(User.student(normalized, passwordEncoder.encode(password), fullName));
    } catch (DataIntegrityViolationException e) {
      // Another request registered the same email between the check and the insert.
      throw new ApiException(ErrorCode.EMAIL_TAKEN);
    }
    return signIn(user, UUID.randomUUID());
  }

  @Transactional
  public SignedIn login(String email, String password) {
    var user = users.findByEmail(User.normalizeEmail(email));
    if (user.isEmpty()) {
      passwordEncoder.matches(password, timingDummyHash);
      throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
    }
    var found = user.get();
    if (!passwordEncoder.matches(password, found.getPasswordHash()) || !found.isActive()) {
      throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
    }
    return signIn(found, UUID.randomUUID());
  }

  /**
   * Swaps a refresh token for a new pair. Revocations must survive the error that follows them, so
   * this transaction commits even when it throws {@link ApiException}.
   */
  @Transactional(noRollbackFor = ApiException.class)
  public SignedIn refresh(String refreshToken) {
    var now = clock.instant();
    var current =
        refreshTokens
            .findByTokenHash(TokenService.hash(refreshToken))
            .orElseThrow(AuthService::invalidRefreshToken);
    if (current.isRevoked()) {
      if (current.wasReplaced()) {
        // A token that was already swapped is being used again: assume it was stolen.
        refreshTokens.revokeFamily(current.getFamilyId(), now);
      }
      throw invalidRefreshToken();
    }
    if (current.isExpiredAt(now)) {
      throw invalidRefreshToken();
    }
    var user = users.findById(current.getUserId()).filter(User::isActive);
    if (user.isEmpty()) {
      refreshTokens.revokeFamily(current.getFamilyId(), now);
      throw invalidRefreshToken();
    }
    var next = issueRefreshToken(user.get(), current.getFamilyId());
    current.replaceWith(next.stored(), now);
    return new SignedIn(user.get(), tokens.accessTokenFor(user.get()), next.value());
  }

  /** Ends the session the token belongs to. Unknown tokens are ignored, so this never fails. */
  @Transactional
  public void logout(String refreshToken) {
    refreshTokens
        .findByTokenHash(TokenService.hash(refreshToken))
        .ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), clock.instant()));
  }

  private SignedIn signIn(User user, UUID familyId) {
    var refresh = issueRefreshToken(user, familyId);
    return new SignedIn(user, tokens.accessTokenFor(user), refresh.value());
  }

  private IssuedRefreshToken issueRefreshToken(User user, UUID familyId) {
    var value = tokens.newRefreshTokenValue();
    var stored =
        refreshTokens.save(
            new RefreshToken(
                user.getId(), TokenService.hash(value), familyId, tokens.refreshTokenExpiry()));
    return new IssuedRefreshToken(value, stored);
  }

  private static ApiException invalidRefreshToken() {
    return new ApiException(ErrorCode.REFRESH_TOKEN_INVALID);
  }

  /** The result of signing in: the user and both tokens. */
  public record SignedIn(User user, TokenService.AccessToken accessToken, String refreshToken) {}

  private record IssuedRefreshToken(String value, RefreshToken stored) {}
}
