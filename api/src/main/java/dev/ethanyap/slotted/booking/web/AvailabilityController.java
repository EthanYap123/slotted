package dev.ethanyap.slotted.booking.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ethanyap.slotted.booking.application.AvailabilityService;
import dev.ethanyap.slotted.booking.application.AvailabilityService.CellState;
import dev.ethanyap.slotted.common.ApiTime;
import dev.ethanyap.slotted.common.Principals;
import dev.ethanyap.slotted.room.domain.RoomType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /facilities/{id}/availability}: the day timeline. */
@RestController
public class AvailabilityController {

  private final AvailabilityService availability;

  public AvailabilityController(AvailabilityService availability) {
    this.availability = availability;
  }

  @GetMapping("/api/v1/facilities/{facilityId}/availability")
  public FacilityAvailability get(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID facilityId,
      @RequestParam LocalDate date,
      @RequestParam(required = false) RoomType type) {
    var day = availability.forDay(Principals.userId(jwt), facilityId, date, type);
    var rooms =
        day.rooms().stream()
            .map(
                r ->
                    new RoomAvailability(
                        r.room().getId(),
                        r.room().getName(),
                        r.room().getType(),
                        r.room().getCapacity(),
                        r.room().getMaxBookingMinutes(),
                        r.cells().stream()
                            .map(
                                c -> new Cell(ApiTime.toApi(c.startAt()), c.state(), c.bookingId()))
                            .toList()))
            .toList();
    return new FacilityAvailability(
        facilityId, day.date(), ApiTime.toApi(day.opensAt()), ApiTime.toApi(day.closesAt()), rooms);
  }

  /** Schema {@code FacilityAvailability}. */
  public record FacilityAvailability(
      UUID facilityId,
      LocalDate date,
      OffsetDateTime opensAt,
      OffsetDateTime closesAt,
      List<RoomAvailability> rooms) {}

  /** Schema {@code RoomAvailability}. */
  public record RoomAvailability(
      UUID roomId,
      String roomName,
      RoomType roomType,
      int capacity,
      int maxBookingMinutes,
      List<Cell> cells) {}

  /** Schema {@code AvailabilityCell}. */
  public record Cell(
      OffsetDateTime startAt,
      CellState state,
      @JsonInclude(JsonInclude.Include.NON_NULL) UUID bookingId) {}
}
