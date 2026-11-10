package dev.ethanyap.slotted.room.web;

import dev.ethanyap.slotted.common.IfMatch;
import dev.ethanyap.slotted.common.Principals;
import dev.ethanyap.slotted.facility.application.FacilityAccess;
import dev.ethanyap.slotted.room.application.RoomService;
import dev.ethanyap.slotted.room.application.RoomService.RoomDetails;
import dev.ethanyap.slotted.room.domain.Room;
import dev.ethanyap.slotted.room.domain.RoomType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Room endpoints in docs/api/openapi.yaml. Managers may change rooms in their facilities only. */
@RestController
public class RoomController {

  private final RoomService rooms;
  private final FacilityAccess access;

  public RoomController(RoomService rooms, FacilityAccess access) {
    this.rooms = rooms;
    this.access = access;
  }

  @GetMapping("/api/v1/facilities/{facilityId}/rooms")
  public List<RoomResponse> list(
      @PathVariable UUID facilityId,
      @RequestParam(required = false) RoomType type,
      @RequestParam(defaultValue = "false") boolean includeInactive,
      Authentication authentication) {
    // Only people who manage the facility may see its inactive rooms.
    var showInactive = includeInactive && access.canManage(facilityId, authentication);
    return rooms.list(facilityId, type, showInactive).stream().map(RoomResponse::from).toList();
  }

  @PostMapping("/api/v1/facilities/{facilityId}/rooms")
  @PreAuthorize("@facilityAccess.canManage(#facilityId, authentication)")
  public ResponseEntity<RoomResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID facilityId,
      @Valid @RequestBody RoomInput input) {
    var room =
        rooms.create(
            Principals.userId(jwt),
            facilityId,
            new RoomDetails(
                input.name(), input.type(), input.capacity(), input.maxBookingMinutes()));
    return ResponseEntity.created(URI.create("/api/v1/rooms/" + room.getId()))
        .body(RoomResponse.from(room));
  }

  @GetMapping("/api/v1/rooms/{roomId}")
  public RoomResponse get(@PathVariable UUID roomId) {
    return RoomResponse.from(rooms.get(roomId));
  }

  @PatchMapping("/api/v1/rooms/{roomId}")
  @PreAuthorize("@facilityAccess.canManage(@roomService.facilityOf(#roomId), authentication)")
  public RoomResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID roomId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody RoomPatch patch) {
    var changes =
        new RoomDetails(patch.name(), patch.type(), patch.capacity(), patch.maxBookingMinutes());
    return RoomResponse.from(
        rooms.update(
            Principals.userId(jwt), roomId, IfMatch.parse(ifMatch), changes, patch.active()));
  }

  /** A printable QR code; only the room's managers may see it, as it holds the secret token. */
  @GetMapping(value = "/api/v1/rooms/{roomId}/check-in-qr", produces = "image/svg+xml")
  @PreAuthorize("@facilityAccess.canManage(@roomService.facilityOf(#roomId), authentication)")
  public ResponseEntity<String> checkInQr(@PathVariable UUID roomId) {
    return ResponseEntity.ok()
        .contentType(MediaType.valueOf("image/svg+xml"))
        .cacheControl(CacheControl.noStore())
        .body(rooms.checkInQr(roomId));
  }

  @PostMapping("/api/v1/rooms/{roomId}/check-in-token/rotate")
  @PreAuthorize("@facilityAccess.canManage(@roomService.facilityOf(#roomId), authentication)")
  public ResponseEntity<Void> rotateCheckInToken(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId) {
    rooms.rotateCheckInToken(Principals.userId(jwt), roomId);
    return ResponseEntity.noContent().build();
  }

  /** Schema {@code Room}. The check-in token is never included. */
  public record RoomResponse(
      UUID id,
      UUID facilityId,
      String name,
      RoomType type,
      int capacity,
      int maxBookingMinutes,
      boolean active,
      long version) {

    static RoomResponse from(Room room) {
      return new RoomResponse(
          room.getId(),
          room.getFacilityId(),
          room.getName(),
          room.getType(),
          room.getCapacity(),
          room.getMaxBookingMinutes(),
          room.isActive(),
          room.getVersion());
    }
  }

  /** Schema {@code RoomInput}. Length rules for bookings are checked by the room itself. */
  public record RoomInput(
      @NotBlank @Size(max = 100) String name,
      @NotNull RoomType type,
      @NotNull @Min(1) @Max(500) Integer capacity,
      Integer maxBookingMinutes) {}

  /** Schema {@code RoomPatch}; omitted fields stay as they are. */
  public record RoomPatch(
      @Size(min = 1, max = 100) String name,
      RoomType type,
      @Min(1) @Max(500) Integer capacity,
      Integer maxBookingMinutes,
      Boolean active) {}
}
