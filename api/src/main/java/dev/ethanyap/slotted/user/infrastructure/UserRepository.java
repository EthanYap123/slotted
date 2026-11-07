package dev.ethanyap.slotted.user.infrastructure;

import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.domain.User;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

  /** Expects an email already normalised with {@link User#normalizeEmail(String)}. */
  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);

  boolean existsByRole(Role role);

  /**
   * Loads and locks the user's row until the transaction ends, so one person's concurrent bookings
   * run one at a time and cannot beat their quotas together (ADR-0001).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id = :id")
  Optional<User> lockById(UUID id);

  /**
   * The same lock, but empty straight away if someone else holds it (Postgres {@code SKIP LOCKED}),
   * for callers that must not wait because they already hold a room (ADR-0007).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("select u from User u where u.id = :id")
  Optional<User> tryLockById(UUID id);
}
