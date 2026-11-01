package dev.ethanyap.slotted.facility.application;

import dev.ethanyap.slotted.facility.infrastructure.FacilityRepository;
import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.domain.User;
import dev.ethanyap.slotted.user.infrastructure.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may manage what, for {@code @PreAuthorize} checks such as
 * {@code @facilityAccess.canManage(#facilityId, authentication)} (ADR-0002). Reads the user's
 * current role and assignments from the database, so a change applies at once rather than when
 * their access token expires.
 */
@Component("facilityAccess")
public class FacilityAccess {

  private final UserRepository users;
  private final FacilityRepository facilities;

  public FacilityAccess(UserRepository users, FacilityRepository facilities) {
    this.users = users;
    this.facilities = facilities;
  }

  /** Active admins only. */
  @Transactional(readOnly = true)
  public boolean isAdmin(Authentication authentication) {
    return activeUser(authentication).filter(u -> u.getRole() == Role.ADMIN).isPresent();
  }

  /** Active admins, and active managers assigned to this facility. */
  @Transactional(readOnly = true)
  public boolean canManage(UUID facilityId, Authentication authentication) {
    return activeUser(authentication)
        .filter(
            u ->
                u.getRole() == Role.ADMIN
                    || (u.getRole() == Role.MANAGER
                        && facilities.isManagedBy(facilityId, u.getId())))
        .isPresent();
  }

  private Optional<User> activeUser(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return Optional.empty();
    }
    try {
      return users.findById(UUID.fromString(authentication.getName())).filter(User::isActive);
    } catch (IllegalArgumentException notAUserId) {
      return Optional.empty();
    }
  }
}
