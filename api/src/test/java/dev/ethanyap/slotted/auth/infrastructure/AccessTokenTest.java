package dev.ethanyap.slotted.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ethanyap.slotted.auth.application.TokenService;
import dev.ethanyap.slotted.user.domain.User;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/** Access tokens expire after 15 minutes (ADR-0002), checked with a fixed clock. */
class AccessTokenTest {

  private static final Instant ISSUED = Instant.parse("2026-11-03T06:00:00Z");

  private final AuthProperties properties =
      new AuthProperties(
          "unit-test-signing-key-not-a-real-secret",
          "slotted",
          Duration.ofMinutes(15),
          Duration.ofDays(7));

  @Test
  void tokenCarriesUserAndRoleAndIsValidUntilExpiry() throws Exception {
    var user = studentWithId(UUID.randomUUID());
    var token = tokenIssuedAt(ISSUED, user);

    var decoded = decoderAt(ISSUED.plus(Duration.ofMinutes(14))).decode(token.value());

    assertThat(decoded.getSubject()).isEqualTo(user.getId().toString());
    assertThat(decoded.getClaimAsString("role")).isEqualTo("STUDENT");
    assertThat(token.expiresAt()).isEqualTo(ISSUED.plus(Duration.ofMinutes(15)));
  }

  @Test
  void tokenIsRejectedAfterExpiryPlusClockSkew() throws Exception {
    var token = tokenIssuedAt(ISSUED, studentWithId(UUID.randomUUID()));
    var afterExpiry = ISSUED.plus(Duration.ofMinutes(15)).plus(JwtConfig.CLOCK_SKEW).plusSeconds(1);

    assertThatThrownBy(() -> decoderAt(afterExpiry).decode(token.value()))
        .isInstanceOf(JwtValidationException.class);
  }

  @Test
  void tokenSignedWithAnotherKeyIsRejected() throws Exception {
    var other =
        new AuthProperties(
            "a-different-signing-key-for-this-test-only",
            "slotted",
            Duration.ofMinutes(15),
            Duration.ofDays(7));
    var token =
        new TokenService(
                new JwtConfig().jwtEncoder(other), other, Clock.fixed(ISSUED, ZoneOffset.UTC))
            .accessTokenFor(studentWithId(UUID.randomUUID()));

    assertThatThrownBy(() -> decoderAt(ISSUED).decode(token.value()))
        .hasMessageContaining("signature");
  }

  private TokenService.AccessToken tokenIssuedAt(Instant at, User user) {
    var encoder = new JwtConfig().jwtEncoder(properties);
    return new TokenService(encoder, properties, Clock.fixed(at, ZoneOffset.UTC))
        .accessTokenFor(user);
  }

  private org.springframework.security.oauth2.jwt.JwtDecoder decoderAt(Instant at) {
    return JwtConfig.decoder(properties, Clock.fixed(at, ZoneOffset.UTC));
  }

  private static User studentWithId(UUID id) throws Exception {
    var user = User.student("someone@example.com", "{noop}unused", "Someone");
    Field field = User.class.getDeclaredField("id");
    field.setAccessible(true);
    field.set(user, id);
    return user;
  }
}
