package dev.ethanyap.slotted.booking.web;

import dev.ethanyap.slotted.booking.application.ScheduleService;
import dev.ethanyap.slotted.booking.application.ScheduleService.Impact;
import dev.ethanyap.slotted.booking.application.ScheduleService.NewBlackout;
import dev.ethanyap.slotted.booking.domain.Blackout;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.common.ApiTime;
import dev.ethanyap.slotted.common.Principals;
import dev.ethanyap.slotted.facility.web.FacilityController.OpeningHoursDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Opening hours and blackouts in docs/api/openapi.yaml: changes that can cancel bookings. */
@RestController
public class ScheduleController {

  private final ScheduleService schedule;
  private final BookingPolicy policy;

  public ScheduleController(ScheduleService schedule, BookingPolicy policy) {
    this.schedule = schedule;
    this.policy = policy;
  }

  @PutMapping("/api/v1/facilities/{facilityId}/opening-hours")
  @PreAuthorize("@facilityAccess.canManage(#facilityId, authentication)")
  public ImpactResponse replaceOpeningHours(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID facilityId,
      @RequestParam(defaultValue = "false") boolean dryRun,
      @RequestBody @Size(max = 7) List<@Valid @NotNull OpeningHoursDto> hours) {
    var periods = hours.stream().map(OpeningHoursDto::toPeriod).toList();
    return ImpactResponse.from(
        schedule.changeOpeningHours(Principals.userId(jwt), facilityId, periods, dryRun), policy);
  }

  @GetMapping("/api/v1/facilities/{facilityId}/blackouts")
  public List<BlackoutResponse> listBlackouts(
      @PathVariable UUID facilityId,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to) {
    return schedule.listBlackouts(facilityId, instant(from), instant(to)).stream()
        .map(BlackoutResponse::from)
        .toList();
  }

  /** A dry run answers 200 with the impact; a real one answers 201 with the blackout. */
  @PostMapping("/api/v1/facilities/{facilityId}/blackouts")
  @PreAuthorize("@facilityAccess.canManage(#facilityId, authentication)")
  public ResponseEntity<Object> createBlackout(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID facilityId,
      @RequestParam(defaultValue = "false") boolean dryRun,
      @Valid @RequestBody BlackoutInput input) {
    var outcome =
        schedule.createBlackout(
            Principals.userId(jwt),
            facilityId,
            new NewBlackout(
                input.roomId(),
                input.startAt().toInstant(),
                input.endAt().toInstant(),
                input.reason()),
            dryRun);
    if (dryRun) {
      return ResponseEntity.ok(ImpactResponse.from(outcome.impact(), policy));
    }
    var created = outcome.created();
    return ResponseEntity.created(URI.create("/api/v1/blackouts/" + created.getId()))
        .body(BlackoutResponse.from(created));
  }

  @DeleteMapping("/api/v1/blackouts/{blackoutId}")
  @PreAuthorize(
      "@facilityAccess.canManage(@scheduleService.facilityOfBlackout(#blackoutId), authentication)")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteBlackout(@PathVariable UUID blackoutId) {
    schedule.deleteBlackout(blackoutId);
  }

  private static Instant instant(OffsetDateTime time) {
    return time == null ? null : time.toInstant();
  }

  /** Schema {@code BlackoutInput}. */
  public record BlackoutInput(
      UUID roomId,
      @NotNull OffsetDateTime startAt,
      @NotNull OffsetDateTime endAt,
      @NotBlank @Size(max = 200) String reason) {}

  /** Schema {@code Blackout}. */
  public record BlackoutResponse(
      UUID id,
      UUID facilityId,
      UUID roomId,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      String reason) {

    static BlackoutResponse from(Blackout blackout) {
      return new BlackoutResponse(
          blackout.getId(),
          blackout.getFacilityId(),
          blackout.getRoomId(),
          ApiTime.toApi(blackout.getStartAt()),
          ApiTime.toApi(blackout.getEndAt()),
          blackout.getReason());
    }
  }

  /** Schema {@code ImpactPreview}. */
  public record ImpactResponse(
      boolean applied, List<BookingResponse> affectedBookings, int affectedWaitlistEntries) {

    static ImpactResponse from(Impact impact, BookingPolicy policy) {
      return new ImpactResponse(
          impact.applied(),
          impact.affectedBookings().stream().map(v -> BookingResponse.from(v, policy)).toList(),
          impact.affectedWaitlistEntries());
    }
  }
}
