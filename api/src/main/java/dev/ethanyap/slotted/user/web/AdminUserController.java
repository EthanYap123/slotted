package dev.ethanyap.slotted.user.web;

import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.common.PageResponse;
import dev.ethanyap.slotted.common.Principals;
import dev.ethanyap.slotted.user.application.UserAdminService;
import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.domain.UserStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /admin/users} in docs/api/openapi.yaml. Admin only, checked twice: URL rule and here. */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

  private final UserAdminService admin;

  public AdminUserController(UserAdminService admin) {
    this.admin = admin;
  }

  @GetMapping
  public PageResponse<UserResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) @Size(max = 100) String q,
      @RequestParam(required = false) Role role,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    var pageable = PageRequest.of(page, size, Sort.by("email"));
    return PageResponse.of(
        admin.search(Principals.userId(jwt), q, role, pageable), UserResponse::from);
  }

  @PatchMapping("/{userId}")
  public UserResponse update(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId, @RequestBody UserPatch patch) {
    if (patch.isEmpty()) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED, "Send at least one of role, status or liftSuspension.");
    }
    var changes =
        new UserAdminService.UserChanges(
            patch.role(), patch.status(), Boolean.TRUE.equals(patch.liftSuspension()));
    return UserResponse.from(admin.update(Principals.userId(jwt), userId, changes));
  }

  /** Body of {@code PATCH /admin/users/{userId}} (schema {@code UserPatch}). */
  public record UserPatch(Role role, UserStatus status, Boolean liftSuspension) {

    boolean isEmpty() {
      return role == null && status == null && liftSuspension == null;
    }
  }
}
