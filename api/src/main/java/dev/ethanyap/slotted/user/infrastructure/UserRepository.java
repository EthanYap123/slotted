package dev.ethanyap.slotted.user.infrastructure;

import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

  /** Expects an email already normalised with {@link User#normalizeEmail(String)}. */
  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);

  boolean existsByRole(Role role);
}
