package dev.ethanyap.slotted.auth.web;

import dev.ethanyap.slotted.auth.application.AuthService;
import dev.ethanyap.slotted.common.ApiTime;
import dev.ethanyap.slotted.user.application.ManagedFacilities;
import dev.ethanyap.slotted.user.application.Suspensions;
import dev.ethanyap.slotted.user.web.CurrentUserResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints under {@code /auth} in docs/api/openapi.yaml. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService auth;
  private final ManagedFacilities managedFacilities;
  private final Suspensions suspensions;

  public AuthController(
      AuthService auth, ManagedFacilities managedFacilities, Suspensions suspensions) {
    this.auth = auth;
    this.managedFacilities = managedFacilities;
    this.suspensions = suspensions;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
    return respond(auth.register(request.email(), request.password(), request.fullName()));
  }

  @PostMapping("/login")
  public AuthResponse login(@Valid @RequestBody LoginRequest request) {
    return respond(auth.login(request.email(), request.password()));
  }

  @PostMapping("/refresh")
  public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
    return respond(auth.refresh(request.refreshToken()));
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@Valid @RequestBody RefreshRequest request) {
    auth.logout(request.refreshToken());
  }

  private AuthResponse respond(AuthService.SignedIn signedIn) {
    var userId = signedIn.user().getId();
    var managed = managedFacilities.facilityIdsManagedBy(userId);
    var suspendedUntil = suspensions.suspendedUntil(List.of(userId)).get(userId);
    return AuthResponse.from(signedIn, managed, suspendedUntil);
  }

  /** Body of {@code POST /auth/register}. Password strength is checked by the password policy. */
  public record RegisterRequest(
      @NotBlank @Email @Size(max = 254) String email,
      @NotNull @Size(max = 128) String password,
      @NotBlank @Size(max = 100) String fullName) {

    /** Pasted emails often carry spaces; trim before validating. */
    public RegisterRequest {
      email = email == null ? null : email.strip();
    }
  }

  /** Body of {@code POST /auth/login}. */
  public record LoginRequest(
      @NotBlank @Email @Size(max = 254) String email, @NotNull @Size(max = 128) String password) {

    public LoginRequest {
      email = email == null ? null : email.strip();
    }
  }

  /** Body of {@code POST /auth/refresh} and {@code POST /auth/logout}. */
  public record RefreshRequest(@NotBlank @Size(max = 128) String refreshToken) {}

  /** Tokens and the signed-in user (schema {@code AuthResponse}). */
  public record AuthResponse(
      String accessToken,
      OffsetDateTime accessTokenExpiresAt,
      String refreshToken,
      CurrentUserResponse user) {

    static AuthResponse from(
        AuthService.SignedIn signedIn, List<UUID> managedFacilityIds, Instant suspendedUntil) {
      return new AuthResponse(
          signedIn.accessToken().value(),
          ApiTime.toApi(signedIn.accessToken().expiresAt()),
          signedIn.refreshToken(),
          CurrentUserResponse.from(signedIn.user(), managedFacilityIds, suspendedUntil));
    }
  }
}
