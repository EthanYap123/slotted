package dev.ethanyap.slotted.audit.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * {@code slotted.audit.*}.
 *
 * @param retention how long audit records are kept before the sweep deletes them (BR-33)
 */
@ConfigurationProperties("slotted.audit")
public record AuditProperties(Duration retention) {

  public AuditProperties {
    retention = retention == null ? Duration.ofDays(180) : retention;
    if (retention.isNegative() || retention.isZero()) {
      throw new IllegalArgumentException("slotted.audit.retention must be positive");
    }
  }

  /** Registers the properties. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AuditProperties.class)
  static class Config {}
}
