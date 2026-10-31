package dev.ethanyap.slotted.common;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the caller's identity from a verified access token. */
public final class Principals {

  private Principals() {}

  /** The user ID, stored as the token's subject. */
  public static UUID userId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
