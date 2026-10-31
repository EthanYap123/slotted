package dev.ethanyap.slotted.user.infrastructure;

import dev.ethanyap.slotted.user.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

  /** Expects an email already normalised with {@link User#normalizeEmail(String)}. */
  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);
}
