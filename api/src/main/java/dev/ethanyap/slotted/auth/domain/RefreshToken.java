package dev.ethanyap.slotted.auth.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One refresh token, stored only as a hash (ADR-0002). Each sign-in starts a family; every refresh
 * replaces the token with a new one in the same family. A replaced token that is used again means
 * it was copied, so the whole family is revoked.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private UUID userId;
  private String tokenHash;
  private UUID familyId;
  private Instant expiresAt;
  private Instant revokedAt;
  private UUID replacedById;

  @CreationTimestamp private Instant createdAt;

  protected RefreshToken() {}

  public RefreshToken(UUID userId, String tokenHash, UUID familyId, Instant expiresAt) {
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.familyId = familyId;
    this.expiresAt = expiresAt;
  }

  public boolean isRevoked() {
    return revokedAt != null;
  }

  /** True when this token was swapped for a newer one, so using it again is a replay. */
  public boolean wasReplaced() {
    return replacedById != null;
  }

  public boolean isExpiredAt(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public void replaceWith(RefreshToken next, Instant now) {
    this.revokedAt = now;
    this.replacedById = next.getId();
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getFamilyId() {
    return familyId;
  }
}
