package dev.ethanyap.slotted.user.web;

import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.common.Principals;
import dev.ethanyap.slotted.user.application.ManagedFacilities;
import dev.ethanyap.slotted.user.application.Suspensions;
import dev.ethanyap.slotted.user.infrastructure.UserRepository;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

  private final UserRepository users;
  private final ManagedFacilities managedFacilities;
  private final Suspensions suspensions;

  public MeController(
      UserRepository users, ManagedFacilities managedFacilities, Suspensions suspensions) {
    this.users = users;
    this.managedFacilities = managedFacilities;
    this.suspensions = suspensions;
  }

  @GetMapping("/api/v1/me")
  @Transactional(readOnly = true)
  public CurrentUserResponse me(@AuthenticationPrincipal Jwt jwt) {
    return users
        .findById(Principals.userId(jwt))
        .map(
            u ->
                CurrentUserResponse.from(
                    u,
                    managedFacilities.facilityIdsManagedBy(u.getId()),
                    suspensions.suspendedUntil(List.of(u.getId())).get(u.getId())))
        .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
  }
}
