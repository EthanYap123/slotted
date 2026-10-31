package dev.ethanyap.slotted.auth.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The first admin account, created on startup when no admin exists ({@code slotted.bootstrap-admin
 * .*}, set with {@code ADMIN_EMAIL}, {@code ADMIN_PASSWORD} and {@code ADMIN_NAME}). Leave the
 * email empty to skip.
 */
@ConfigurationProperties("slotted.bootstrap-admin")
public record BootstrapAdminProperties(String email, String password, String fullName) {

  public boolean isConfigured() {
    return email != null && !email.isBlank();
  }
}
