package dev.ethanyap.slotted.room.application;

import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.common.IfMatch;
import dev.ethanyap.slotted.facility.infrastructure.FacilityRepository;
import dev.ethanyap.slotted.room.domain.Room;
import dev.ethanyap.slotted.room.domain.RoomType;
import dev.ethanyap.slotted.room.infrastructure.CheckInProperties;
import dev.ethanyap.slotted.room.infrastructure.RoomRepository;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rooms in a facility (PRD MG-1). Access is checked by callers. */
@Service
public class RoomService {

  private static final int CHECK_IN_TOKEN_BYTES = 24;

  private final RoomRepository rooms;
  private final FacilityRepository facilities;
  private final CheckInProperties checkIn;
  private final SecureRandom random = new SecureRandom();

  public RoomService(
      RoomRepository rooms, FacilityRepository facilities, CheckInProperties checkIn) {
    this.checkIn = checkIn;
    this.rooms = rooms;
    this.facilities = facilities;
  }

  /** Rooms sorted by name; inactive ones only when asked for. */
  @Transactional(readOnly = true)
  public List<Room> list(UUID facilityId, RoomType type, boolean includeInactive) {
    requireFacility(facilityId);
    return rooms.findByFacilityIdOrderByName(facilityId).stream()
        .filter(room -> includeInactive || room.isActive())
        .filter(room -> type == null || room.getType() == type)
        .toList();
  }

  @Transactional(readOnly = true)
  public Room get(UUID roomId) {
    return rooms
        .findById(roomId)
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No room with this ID."));
  }

  /** Facility the room belongs to, for access checks; null if the room does not exist. */
  @Transactional(readOnly = true)
  public UUID facilityOf(UUID roomId) {
    return rooms.findById(roomId).map(Room::getFacilityId).orElse(null);
  }

  @Transactional
  public Room create(UUID facilityId, RoomDetails details) {
    requireFacility(facilityId);
    var room =
        new Room(
            facilityId,
            details.name(),
            details.type(),
            details.capacity() == null ? 0 : details.capacity(),
            details.maxBookingMinutes(),
            newCheckInToken());
    if (rooms.existsByFacilityIdAndNameIgnoreCase(facilityId, room.getName())) {
      throw nameTaken();
    }
    return saveUniquely(room);
  }

  /** Applies the fields that are not null. Deactivating keeps existing bookings (BR-10). */
  @Transactional
  public Room update(UUID roomId, Long expectedVersion, RoomDetails changes, Boolean active) {
    var room = get(roomId);
    IfMatch.check(expectedVersion, room.getVersion());
    if (changes.name() != null) {
      room.rename(changes.name());
      if (rooms.existsByFacilityIdAndNameIgnoreCaseAndIdNot(
          room.getFacilityId(), room.getName(), roomId)) {
        throw nameTaken();
      }
    }
    if (changes.type() != null) {
      room.changeType(changes.type());
    }
    if (changes.capacity() != null) {
      room.changeCapacity(changes.capacity());
    }
    if (changes.maxBookingMinutes() != null) {
      room.changeMaxBookingMinutes(changes.maxBookingMinutes());
    }
    if (active != null) {
      room.setActive(active);
    }
    return saveUniquely(room);
  }

  /**
   * A printable QR code for the room (MG-5). It opens the web app's check-in page with the room's
   * current token.
   */
  @Transactional(readOnly = true)
  public String checkInQr(UUID roomId) {
    var room = get(roomId);
    var base = checkIn.webUrl().replaceAll("/+$", "");
    var link = base + "/check-in?token=" + room.getCheckInToken();
    return QrCodes.svg(link, room.getName());
  }

  /** Issues a new token, so printed copies of the old QR code stop working at once (MG-5). */
  @Transactional
  public void rotateCheckInToken(UUID roomId) {
    get(roomId).replaceCheckInToken(newCheckInToken());
  }

  private void requireFacility(UUID facilityId) {
    if (!facilities.existsById(facilityId)) {
      throw new ApiException(ErrorCode.NOT_FOUND, "No facility with this ID.");
    }
  }

  /** A random, URL-safe secret for the room's QR code. */
  private String newCheckInToken() {
    var bytes = new byte[CHECK_IN_TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private Room saveUniquely(Room room) {
    try {
      return rooms.saveAndFlush(room);
    } catch (DataIntegrityViolationException e) {
      throw nameTaken();
    }
  }

  private static ApiException nameTaken() {
    return new ApiException(
        ErrorCode.NAME_TAKEN, "This facility already has a room with this name.");
  }

  /** Room fields as sent by a manager; null means "not given". */
  public record RoomDetails(
      String name, RoomType type, Integer capacity, Integer maxBookingMinutes) {}
}
