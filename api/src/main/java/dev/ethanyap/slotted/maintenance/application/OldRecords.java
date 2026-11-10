package dev.ethanyap.slotted.maintenance.application;

import dev.ethanyap.slotted.audit.application.AuditLog;
import dev.ethanyap.slotted.auth.application.ExpiredRefreshTokens;
import dev.ethanyap.slotted.maintenance.infrastructure.MaintenanceProperties;
import dev.ethanyap.slotted.notification.application.EmailDispatcher;
import org.springframework.stereotype.Component;

/**
 * Deletes rows that are no longer needed, each kind in a transaction of its own: refresh tokens
 * that expired a while ago, emails that are finished with, and audit records past their retention
 * period (docs/ERD.md, BR-33).
 */
@Component
class OldRecords {

  private final ExpiredRefreshTokens refreshTokens;
  private final EmailDispatcher emails;
  private final AuditLog audit;
  private final MaintenanceProperties properties;

  OldRecords(
      ExpiredRefreshTokens refreshTokens,
      EmailDispatcher emails,
      AuditLog audit,
      MaintenanceProperties properties) {
    this.refreshTokens = refreshTokens;
    this.emails = emails;
    this.audit = audit;
    this.properties = properties;
  }

  /** Returns how many rows were deleted. */
  int purge() {
    return refreshTokens.purge(properties.refreshTokenRetention())
        + emails.purgeFinished()
        + audit.purgeExpired();
  }
}
