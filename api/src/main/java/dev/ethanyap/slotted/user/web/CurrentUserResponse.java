package dev.ethanyap.slotted.user.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import dev.ethanyap.slotted.user.domain.User;
import java.util.List;
import java.util.UUID;

/** The signed-in user (schema {@code CurrentUser}): a user plus the facilities they manage. */
public record CurrentUserResponse(@JsonUnwrapped UserResponse user, List<UUID> managedFacilityIds) {

  public static CurrentUserResponse from(User user) {
    // Facility assignments arrive with facilities (issue #37).
    return new CurrentUserResponse(UserResponse.from(user), List.of());
  }
}
