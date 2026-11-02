package dev.ethanyap.slotted.user.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import dev.ethanyap.slotted.user.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The signed-in user (schema {@code CurrentUser}): a user plus the facilities they manage. */
public record CurrentUserResponse(@JsonUnwrapped UserResponse user, List<UUID> managedFacilityIds) {

  public static CurrentUserResponse from(
      User user, List<UUID> managedFacilityIds, Instant suspendedUntil) {
    return new CurrentUserResponse(
        UserResponse.from(user, suspendedUntil), List.copyOf(managedFacilityIds));
  }
}
