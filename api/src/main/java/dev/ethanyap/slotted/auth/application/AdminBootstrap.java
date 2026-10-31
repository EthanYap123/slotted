package dev.ethanyap.slotted.auth.application;

import dev.ethanyap.slotted.auth.domain.PasswordPolicy;
import dev.ethanyap.slotted.auth.infrastructure.BootstrapAdminProperties;
import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.domain.User;
import dev.ethanyap.slotted.user.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first admin from environment variables, because only an admin can make other admins.
 * Does nothing once any admin exists, so it is safe on every start.
 */
@Component
public class AdminBootstrap {

  private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

  private final BootstrapAdminProperties properties;
  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final PasswordPolicy passwordPolicy;

  public AdminBootstrap(
      BootstrapAdminProperties properties,
      UserRepository users,
      PasswordEncoder passwordEncoder,
      PasswordPolicy passwordPolicy) {
    this.properties = properties;
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.passwordPolicy = passwordPolicy;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void createFirstAdminIfMissing() {
    if (!properties.isConfigured() || users.existsByRole(Role.ADMIN)) {
      return;
    }
    var email = User.normalizeEmail(properties.email());
    if (users.existsByEmail(email)) {
      log.warn("Not creating the first admin: {} already has an account", email);
      return;
    }
    var password = properties.password() == null ? "" : properties.password();
    var problem = passwordPolicy.problemWith(password);
    if (problem.isPresent()) {
      throw new IllegalStateException("ADMIN_PASSWORD is not acceptable: " + problem.get());
    }
    var name =
        properties.fullName() == null || properties.fullName().isBlank()
            ? "Administrator"
            : properties.fullName();
    users.save(User.admin(email, passwordEncoder.encode(password), name));
    log.info("Created the first admin account for {}", email);
  }
}
