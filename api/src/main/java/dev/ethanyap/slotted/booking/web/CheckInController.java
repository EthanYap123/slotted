package dev.ethanyap.slotted.booking.web;

import dev.ethanyap.slotted.booking.application.CheckInService;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.common.Principals;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code /check-ins} and {@code /bookings/{bookingId}/end} in docs/api/openapi.yaml. */
@RestController
public class CheckInController {

  private final CheckInService checkIns;
  private final BookingPolicy policy;

  public CheckInController(CheckInService checkIns, BookingPolicy policy) {
    this.checkIns = checkIns;
    this.policy = policy;
  }

  @PostMapping("/api/v1/check-ins")
  public BookingResponse checkIn(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CheckInRequest request) {
    return BookingResponse.from(checkIns.checkIn(Principals.userId(jwt), request.token()), policy);
  }

  @PostMapping("/api/v1/bookings/{bookingId}/end")
  public BookingResponse endEarly(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID bookingId) {
    return BookingResponse.from(checkIns.endEarly(Principals.userId(jwt), bookingId), policy);
  }

  /** The token from the room's QR code. */
  public record CheckInRequest(@NotNull @Size(min = 16, max = 128) String token) {}
}
