package dev.ethanyap.slotted.maintenance.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * {@code slotted.maintenance.*}.
 *
 * @param key the shared secret the scheduled workflow sends; empty turns the sweep endpoint off
 * @param minInterval the shortest time between two sweeps; closer calls get {@code 429}
 * @param refreshTokenRetention how long expired refresh tokens are kept before they are deleted
 */
@Validated
@ConfigurationProperties("slotted.maintenance")
public record MaintenanceProperties(
    String key, Duration minInterval, Duration refreshTokenRetention) {

  /** At least this long, so the key cannot be guessed. */
  public static final int MIN_KEY_LENGTH = 32;

  public MaintenanceProperties {
    key = key == null ? "" : key.strip();
    if (!key.isEmpty() && key.length() < MIN_KEY_LENGTH) {
      throw new IllegalArgumentException(
          "MAINTENANCE_KEY must be at least " + MIN_KEY_LENGTH + " characters, or left empty.");
    }
    minInterval = minInterval == null ? Duration.ofMinutes(1) : minInterval;
    refreshTokenRetention =
        refreshTokenRetention == null ? Duration.ofDays(1) : refreshTokenRetention;
  }

  /** Registers the properties. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(MaintenanceProperties.class)
  static class Config {}
}
