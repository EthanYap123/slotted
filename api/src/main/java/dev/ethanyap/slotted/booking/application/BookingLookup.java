package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.Blackout;
import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.BookingRules.RoomFacts;
import dev.ethanyap.slotted.booking.infrastructure.BlackoutRepository;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.facility.domain.Facility;
import dev.ethanyap.slotted.facility.infrastructure.FacilityRepository;
import dev.ethanyap.slotted.room.domain.Room;
import dev.ethanyap.slotted.room.infrastructure.RoomRepository;
import dev.ethanyap.slotted.user.domain.User;
import dev.ethanyap.slotted.user.infrastructure.UserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Component;

/**
 * Loads the rooms, facilities and names that bookings refer to, and builds {@link BookingView}s.
 */
@Component
class BookingLookup {

  private final RoomRepository rooms;
  private final FacilityRepository facilities;
  private final UserRepository users;
  private final BlackoutRepository blackouts;
  private final BookingPolicy policy;

  BookingLookup(
      RoomRepository rooms,
      FacilityRepository facilities,
      UserRepository users,
      BlackoutRepository blackouts,
      BookingPolicy policy) {
    this.rooms = rooms;
    this.facilities = facilities;
    this.users = users;
    this.blackouts = blackouts;
    this.policy = policy;
  }

  User activeUser(UUID userId) {
    return users
        .findById(userId)
        .filter(User::isActive)
        .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
  }

  /** The booker, locked until the transaction ends so their quotas cannot be raced (ADR-0001). */
  User lockActiveUser(UUID userId) {
    return users
        .lockById(userId)
        .filter(User::isActive)
        .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
  }

  /** The same lock without waiting; empty if the user is busy or no longer active. */
  Optional<User> tryLockActiveUser(UUID userId) {
    return users.tryLockById(userId).filter(User::isActive);
  }

  /** The room whose current QR code holds this token. */
  Optional<Room> roomByCheckInToken(String token) {
    return rooms.findByCheckInToken(token);
  }

  Room room(UUID roomId) {
    return rooms
        .findById(roomId)
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No room with this ID."));
  }

  /** The room, locked until the transaction ends (ADR-0006). */
  Room lockRoom(UUID roomId) {
    return rooms
        .lockById(roomId)
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No room with this ID."));
  }

  /** Every room in a facility, locked in a fixed order until the transaction ends (ADR-0006). */
  List<Room> lockRoomsOf(UUID facilityId) {
    return rooms.lockByFacilityId(facilityId);
  }

  /** Why the room is closed for this period, if a blackout covers any of it (BR-5). */
  Optional<String> blackoutReason(Room room, Instant from, Instant to) {
    return blackouts.findOverlapping(room.getFacilityId(), from, to).stream()
        .filter(b -> b.covers(room.getId(), from, to))
        .map(Blackout::getReason)
        .findFirst();
  }

  /** What the booking rules need to know about a room for this period. */
  RoomFacts roomFacts(Room room, Facility facility, Instant start, Instant end) {
    var zone = ZoneId.of(facility.getTimeZone());
    var day = start.atZone(zone).getDayOfWeek();
    return new RoomFacts(
        room.isActive(),
        room.getCapacity(),
        room.getMaxBookingMinutes(),
        zone,
        facility.getOpeningHours().stream().filter(p -> p.dayOfWeek() == day).findFirst(),
        blackoutReason(room, start, end));
  }

  /** Names of these rooms, by ID. */
  Map<UUID, String> roomNames(Collection<UUID> roomIds) {
    return rooms.findAllById(new HashSet<>(roomIds)).stream()
        .collect(Collectors.toMap(Room::getId, Room::getName));
  }

  Facility facilityOf(Room room) {
    return facilities.findById(room.getFacilityId()).orElseThrow();
  }

  List<UUID> roomIdsIn(Collection<UUID> facilityIds) {
    return rooms.findByFacilityIdIn(facilityIds).stream().map(Room::getId).toList();
  }

  String nameOf(UUID userId) {
    return users.findById(userId).map(User::getFullName).orElse(null);
  }

  BookingView view(Booking booking, Room room, Facility facility, Instant now, String bookerName) {
    return new BookingView(
        booking, room, facility, booking.effectiveStatus(now, policy), bookerName);
  }

  /** Views for a page of bookings. */
  Page<BookingView> views(Page<Booking> page, Instant now, boolean withNames) {
    return new PageImpl<>(
        viewsOf(page.getContent(), now, withNames), page.getPageable(), page.getTotalElements());
  }

  /** Views for a list of bookings, loading rooms, facilities and names in a few queries. */
  List<BookingView> viewsOf(List<Booking> bookings, Instant now, boolean withNames) {
    var roomIds = new HashSet<UUID>();
    var userIds = new HashSet<UUID>();
    bookings.forEach(
        b -> {
          roomIds.add(b.getRoomId());
          userIds.add(b.getUserId());
        });
    var roomsById = byId(rooms.findAllById(roomIds), Room::getId);
    var facilitiesById =
        byId(
            facilities.findAllById(
                roomsById.values().stream().map(Room::getFacilityId).collect(Collectors.toSet())),
            Facility::getId);
    var names =
        withNames
            ? users.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName))
            : Map.<UUID, String>of();
    return bookings.stream()
        .map(
            b -> {
              var room = roomsById.get(b.getRoomId());
              return view(
                  b, room, facilitiesById.get(room.getFacilityId()), now, names.get(b.getUserId()));
            })
        .toList();
  }

  private static <T> Map<UUID, T> byId(List<T> items, Function<T, UUID> id) {
    return items.stream().collect(Collectors.toMap(id, Function.identity()));
  }
}
