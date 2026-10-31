package dev.ethanyap.slotted.user.web;

import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.common.Principals;
import dev.ethanyap.slotted.user.infrastructure.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

  private final UserRepository users;

  public MeController(UserRepository users) {
    this.users = users;
  }

  @GetMapping("/api/v1/me")
  @Transactional(readOnly = true)
  public CurrentUserResponse me(@AuthenticationPrincipal Jwt jwt) {
    return users
        .findById(Principals.userId(jwt))
        .map(CurrentUserResponse::from)
        .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
  }
}
