package dev.ethanyap.slotted.auth.infrastructure;

import dev.ethanyap.slotted.auth.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  /** Locks the row so two refreshes with the same token cannot both succeed. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /**
   * Deletes tokens that expired before {@code cutoff}; returns how many. A token another one still
   * points to as its replacement is kept until that one goes too, so the reference stays valid.
   */
  @Modifying
  @Query(
      "delete from RefreshToken t where t.expiresAt < :cutoff and not exists"
          + " (select 1 from RefreshToken r where r.replacedById = t.id and r.expiresAt >= :cutoff)")
  int deleteExpiredBefore(Instant cutoff);

  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = :now"
          + " where t.familyId = :familyId and t.revokedAt is null")
  int revokeFamily(UUID familyId, Instant now);

  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = :now"
          + " where t.userId = :userId and t.revokedAt is null")
  int revokeAllForUser(UUID userId, Instant now);
}
