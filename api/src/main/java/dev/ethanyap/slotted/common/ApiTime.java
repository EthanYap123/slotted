package dev.ethanyap.slotted.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Time zone for business rules and API responses (ADR-0003). Instants are stored in UTC and shown
 * to clients as Singapore time with its offset, for example {@code 2026-11-03T14:00:00+08:00}.
 */
public final class ApiTime {

  public static final ZoneId ZONE = ZoneId.of("Asia/Singapore");

  private ApiTime() {}

  public static OffsetDateTime toApi(Instant instant) {
    return instant == null ? null : instant.atZone(ZONE).toOffsetDateTime();
  }
}
