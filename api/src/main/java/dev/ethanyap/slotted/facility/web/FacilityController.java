package dev.ethanyap.slotted.facility.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.ethanyap.slotted.common.IfMatch;
import dev.ethanyap.slotted.common.Principals;
import dev.ethanyap.slotted.facility.application.FacilityService;
import dev.ethanyap.slotted.facility.application.FacilityService.FacilityChanges;
import dev.ethanyap.slotted.facility.domain.Facility;
import dev.ethanyap.slotted.facility.domain.OpeningPeriod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /facilities} in docs/api/openapi.yaml. Anyone signed in can read; writes are scoped.
 * Opening hours are changed through {@code ScheduleController}, because that can cancel bookings.
 */
@RestController
@RequestMapping("/api/v1/facilities")
public class FacilityController {

  private final FacilityService facilities;

  public FacilityController(FacilityService facilities) {
    this.facilities = facilities;
  }

  @GetMapping
  public List<FacilityResponse> list() {
    return facilities.list().stream().map(FacilityResponse::from).toList();
  }

  @GetMapping("/{facilityId}")
  public FacilityResponse get(@PathVariable UUID facilityId) {
    return FacilityResponse.from(facilities.get(facilityId));
  }

  @PostMapping
  @PreAuthorize("@facilityAccess.isAdmin(authentication)")
  public ResponseEntity<FacilityResponse> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FacilityInput input) {
    var facility =
        facilities.create(
            Principals.userId(jwt), input.name(), input.description(), input.location());
    return ResponseEntity.created(URI.create("/api/v1/facilities/" + facility.getId()))
        .body(FacilityResponse.from(facility));
  }

  @PatchMapping("/{facilityId}")
  @PreAuthorize("@facilityAccess.canManage(#facilityId, authentication)")
  public FacilityResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID facilityId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody FacilityPatch patch) {
    var changes = new FacilityChanges(patch.name(), patch.description(), patch.location());
    return FacilityResponse.from(
        facilities.update(Principals.userId(jwt), facilityId, IfMatch.parse(ifMatch), changes));
  }

  @PutMapping("/{facilityId}/managers")
  @PreAuthorize("@facilityAccess.isAdmin(authentication)")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void replaceManagers(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID facilityId,
      @Valid @RequestBody ManagersRequest request) {
    facilities.replaceManagers(Principals.userId(jwt), facilityId, request.userIds());
  }

  /** Schema {@code Facility}. */
  public record FacilityResponse(
      UUID id,
      String name,
      String description,
      String location,
      String timeZone,
      List<OpeningHoursDto> openingHours,
      long version) {

    static FacilityResponse from(Facility facility) {
      return new FacilityResponse(
          facility.getId(),
          facility.getName(),
          facility.getDescription(),
          facility.getLocation(),
          facility.getTimeZone(),
          facility.getOpeningHours().stream().map(OpeningHoursDto::from).toList(),
          facility.getVersion());
    }
  }

  /** Schema {@code FacilityInput}. */
  public record FacilityInput(
      @NotBlank @Size(max = 100) String name,
      @Size(max = 1000) String description,
      @Size(max = 200) String location) {}

  /** Schema {@code FacilityPatch}: omitted fields stay; an empty string clears an optional one. */
  public record FacilityPatch(
      @Size(min = 1, max = 100) String name,
      @Size(max = 1000) String description,
      @Size(max = 200) String location) {}

  /** Schema {@code OpeningHours}. Times are {@code HH:mm} in the facility's zone. */
  public record OpeningHoursDto(
      @NotNull DayOfWeek dayOfWeek,
      @NotNull @JsonFormat(pattern = "HH:mm") LocalTime opensAt,
      @NotNull @JsonFormat(pattern = "HH:mm") LocalTime closesAt) {

    static OpeningHoursDto from(OpeningPeriod period) {
      return new OpeningHoursDto(period.dayOfWeek(), period.opensAt(), period.closesAt());
    }

    public OpeningPeriod toPeriod() {
      return new OpeningPeriod(dayOfWeek, opensAt, closesAt);
    }
  }

  /** Body of {@code PUT /facilities/{id}/managers}. */
  public record ManagersRequest(@NotNull @Size(max = 50) Set<@NotNull UUID> userIds) {}
}
