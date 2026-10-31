package dev.ethanyap.slotted.auth.application;

import dev.ethanyap.slotted.auth.infrastructure.AuthProperties;
import dev.ethanyap.slotted.auth.infrastructure.SecurityConfig;
import dev.ethanyap.slotted.user.domain.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Creates access tokens and refresh token values (ADR-0002). */
@Component
public class TokenService {

  private static final int REFRESH_TOKEN_BYTES = 32;

  private final JwtEncoder encoder;
  private final AuthProperties properties;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public TokenService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
    this.encoder = encoder;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * A signed access token. Facility assignments are not included; they are looked up per request.
   */
  public AccessToken accessTokenFor(User user) {
    var issuedAt = clock.instant();
    var expiresAt = issuedAt.plus(properties.accessTokenTtl());
    var claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .subject(user.getId().toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .id(UUID.randomUUID().toString())
            .claim(SecurityConfig.ROLE_CLAIM, user.getRole().name())
            .build();
    var header = JwsHeader.with(MacAlgorithm.HS256).build();
    var value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new AccessToken(value, expiresAt);
  }

  /** A new random refresh token value, URL-safe, 256 bits. */
  public String newRefreshTokenValue() {
    var bytes = new byte[REFRESH_TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public Instant refreshTokenExpiry() {
    return clock.instant().plus(properties.refreshTokenTtl());
  }

  /** SHA-256 in hex: what is stored instead of the refresh token itself. */
  public static String hash(String refreshToken) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(refreshToken.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }

  /** An access token and when it expires. */
  public record AccessToken(String value, Instant expiresAt) {}
}
