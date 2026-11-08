package dev.ethanyap.slotted.room.infrastructure;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * {@code slotted.check-in.*}. A room's QR code opens {@code <webUrl>/check-in?token=...} in the web
 * app, so it must point at the web app people use, not at the API.
 */
@Validated
@ConfigurationProperties("slotted.check-in")
public record CheckInProperties(@NotBlank String webUrl) {

  /** Registers the properties. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(CheckInProperties.class)
  static class Config {}
}
