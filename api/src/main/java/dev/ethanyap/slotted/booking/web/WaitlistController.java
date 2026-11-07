package dev.ethanyap.slotted.booking.web;

import dev.ethanyap.slotted.booking.application.BookingAttempts;
import dev.ethanyap.slotted.booking.application.WaitlistService;
import dev.ethanyap.slotted.booking.application.WaitlistService.NewEntry;
import dev.ethanyap.slotted.booking.application.WaitlistService.WaitlistView;
import dev.ethanyap.slotted.booking.domain.WaitlistStatus;
import dev.ethanyap.slotted.common.ApiTime;
import dev.ethanyap.slotted.common.Principals;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /waitlist} in docs/api/openapi.yaml. */
@RestController
@RequestMapping("/api/v1/waitlist")
public class WaitlistController {

  private final WaitlistService waitlist;
  private final BookingAttempts attempts;

  public WaitlistController(WaitlistService waitlist, BookingAttempts attempts) {
    this.waitlist = waitlist;
    this.attempts = attempts;
  }

  @PostMapping
  public ResponseEntity<WaitlistResponse> join(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SlotRequest request) {
    var view =
        attempts.joinWaitlist(
            Principals.userId(jwt),
            new NewEntry(
                request.roomId(), request.startAt().toInstant(), request.endAt().toInstant()));
    return ResponseEntity.created(URI.create("/api/v1/waitlist/" + view.entry().getId()))
        .body(WaitlistResponse.from(view));
  }

  @GetMapping("/mine")
  public List<WaitlistResponse> mine(@AuthenticationPrincipal Jwt jwt) {
    return waitlist.mine(Principals.userId(jwt)).stream().map(WaitlistResponse::from).toList();
  }

  @DeleteMapping("/{entryId}")
  public ResponseEntity<Void> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID entryId) {
    waitlist.leave(Principals.userId(jwt), entryId);
    return ResponseEntity.noContent().build();
  }

  /** Schema {@code SlotRequest}. Times need an offset, for example {@code +08:00}. */
  public record SlotRequest(
      @NotNull UUID roomId, @NotNull OffsetDateTime startAt, @NotNull OffsetDateTime endAt) {}

  /** Schema {@code WaitlistEntry}. */
  public record WaitlistResponse(
      UUID id,
      UUID roomId,
      String roomName,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      WaitlistStatus status,
      Long position,
      UUID promotedBookingId,
      OffsetDateTime createdAt) {

    static WaitlistResponse from(WaitlistView view) {
      var entry = view.entry();
      return new WaitlistResponse(
          entry.getId(),
          entry.getRoomId(),
          view.roomName(),
          ApiTime.toApi(entry.getStartAt()),
          ApiTime.toApi(entry.getEndAt()),
          view.status(),
          view.position(),
          entry.getPromotedBookingId(),
          ApiTime.toApi(entry.getCreatedAt()));
    }
  }
}
