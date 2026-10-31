package dev.ethanyap.slotted.user.application;

import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.domain.User;
import dev.ethanyap.slotted.user.domain.UserAccessChanged;
import dev.ethanyap.slotted.user.domain.UserStatus;
import dev.ethanyap.slotted.user.infrastructure.UserRepository;
import dev.ethanyap.slotted.user.infrastructure.UserSearch;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin user management (PRD AD-1). */
@Service
public class UserAdminService {

  private final UserRepository users;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public UserAdminService(UserRepository users, ApplicationEventPublisher events, Clock clock) {
    this.users = users;
    this.events = events;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Page<User> search(UUID actorId, String query, Role role, Pageable pageable) {
    requireActiveAdmin(actorId);
    return users.findAll(UserSearch.matching(query, role), pageable);
  }

  /**
   * Applies the changes that are not null. Changing a role or status ends the user's sessions, so
   * the change applies at their next refresh at the latest (ADR-0002).
   */
  @Transactional
  public User update(UUID actorId, UUID userId, UserChanges changes) {
    requireActiveAdmin(actorId);
    var user =
        users
            .findById(userId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No user with this ID."));
    if (actorId.equals(userId) && changes.removesAdminAccess()) {
      throw new ApiException(
          ErrorCode.LAST_ADMIN,
          "Ask another admin to change your role or status, so there is always an admin.");
    }
    var accessChanged = false;
    if (changes.role() != null && changes.role() != user.getRole()) {
      user.changeRole(changes.role());
      accessChanged = true;
    }
    if (changes.status() != null && changes.status() != user.getStatus()) {
      user.changeStatus(changes.status());
      accessChanged = true;
    }
    if (changes.liftSuspension()) {
      user.liftSuspension(clock.instant());
    }
    if (accessChanged) {
      events.publishEvent(new UserAccessChanged(user.getId()));
    }
    return user;
  }

  /**
   * Checks the database, not only the token: an admin who was demoted or disabled still holds a
   * valid access token for up to 15 minutes, and must not use it here.
   */
  private void requireActiveAdmin(UUID actorId) {
    var isAdmin =
        users.findById(actorId).filter(u -> u.isActive() && u.getRole() == Role.ADMIN).isPresent();
    if (!isAdmin) {
      throw new ApiException(ErrorCode.FORBIDDEN);
    }
  }

  /** Requested changes to a user; null fields are left alone. */
  public record UserChanges(Role role, UserStatus status, boolean liftSuspension) {

    boolean removesAdminAccess() {
      return (role != null && role != Role.ADMIN) || status == UserStatus.DISABLED;
    }
  }
}
