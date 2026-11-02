package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.facility.domain.Facility;
import dev.ethanyap.slotted.facility.infrastructure.FacilityRepository;
import dev.ethanyap.slotted.room.domain.Room;
import dev.ethanyap.slotted.room.infrastructure.RoomRepository;
import dev.ethanyap.slotted.user.domain.User;
import dev.ethanyap.slotted.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * Loads the rooms, facilities and names that bookings refer to, and builds {@link BookingView}s.
 */
@Component
class BookingLookup {

  private final RoomRepository rooms;
  private final FacilityRepository facilities;
  private final UserRepository users;
  private final BookingPolicy policy;

  BookingLookup(
      RoomRepository rooms,
      FacilityRepository facilities,
      UserRepository users,
      BookingPolicy policy) {
    this.rooms = rooms;
    this.facilities = facilities;
    this.users = users;
    this.policy = policy;
  }

  Room room(UUID roomId) {
    return rooms
        .findById(roomId)
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No room with this ID."));
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

  /** Views for a page of bookings, loading rooms, facilities and names in a few queries. */
  Page<BookingView> views(Page<Booking> page, Instant now, boolean withNames) {
    var roomIds = new HashSet<UUID>();
    var userIds = new HashSet<UUID>();
    page.forEach(
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
    return page.map(
        b -> {
          var room = roomsById.get(b.getRoomId());
          return view(
              b, room, facilitiesById.get(room.getFacilityId()), now, names.get(b.getUserId()));
        });
  }

  private static <T> Map<UUID, T> byId(List<T> items, Function<T, UUID> id) {
    return items.stream().collect(Collectors.toMap(id, Function.identity()));
  }
}
