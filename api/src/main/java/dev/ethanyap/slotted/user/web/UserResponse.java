package dev.ethanyap.slotted.user.web;

import dev.ethanyap.slotted.common.ApiTime;
import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.domain.User;
import dev.ethanyap.slotted.user.domain.UserStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A user as the API shows it (schema {@code User}). Never includes the password hash. */
public record UserResponse(
    UUID id,
    String email,
    String fullName,
    Role role,
    UserStatus status,
    OffsetDateTime suspendedUntil,
    OffsetDateTime createdAt) {

  public static UserResponse from(User user) {
    // Suspensions come from no-shows (BR-18), which arrive with bookings.
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.getFullName(),
        user.getRole(),
        user.getStatus(),
        null,
        ApiTime.toApi(user.getCreatedAt()));
  }
}
