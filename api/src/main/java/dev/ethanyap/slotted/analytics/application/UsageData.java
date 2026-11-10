package dev.ethanyap.slotted.analytics.application;

import dev.ethanyap.slotted.analytics.domain.Utilisation.BookingFact;
import dev.ethanyap.slotted.analytics.domain.Utilisation.Interval;
import dev.ethanyap.slotted.analytics.domain.Utilisation.Outcome;
import dev.ethanyap.slotted.analytics.domain.Utilisation.RoomInput;
import dev.ethanyap.slotted.booking.domain.Blackout;
import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.infrastructure.BlackoutRepository;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.facility.domain.Facility;
import dev.ethanyap.slotted.facility.infrastructure.FacilityRepository;
import dev.ethanyap.slotted.room.domain.Room;
import dev.ethanyap.slotted.room.infrastructure.RoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Loads the rooms, opening hours, blackouts and bookings a utilisation report is built from. */
@Component
class UsageData {

  private final RoomRepository rooms;
  private final FacilityRepository facilities;
  private final BlackoutRepository blackouts;
  private final BookingRepository bookings;
  private final BookingPolicy policy;
  private final Clock clock;

  UsageData(
      RoomRepository rooms,
      FacilityRepository facilities,
      BlackoutRepository blackouts,
      BookingRepository bookings,
      BookingPolicy policy,
      Clock clock) {
    this.rooms = rooms;
    this.facilities = facilities;
    this.blackouts = blackouts;
    this.bookings = bookings;
    this.policy = policy;
    this.clock = clock;
  }

  /**
   * The facilities' rooms and their bookings from {@code fromDate} to {@code toDate}, inclusive.
   * Inactive rooms are left out unless they had bookings in the range.
   */
  Inputs load(Collection<UUID> facilityIds, LocalDate fromDate, LocalDate toDate) {
    if (facilityIds.isEmpty()) {
      return new Inputs(List.of(), List.of());
    }
    var byId =
        facilities.findAllById(facilityIds).stream()
            .collect(Collectors.toMap(Facility::getId, Function.identity()));
    var allRooms = rooms.findByFacilityIdIn(byId.keySet());
    if (allRooms.isEmpty()) {
      return new Inputs(List.of(), List.of());
    }
    var zone = ZoneId.of(Facility.DEFAULT_TIME_ZONE);
    var from = fromDate.atStartOfDay(zone).toInstant();
    var to = toDate.plusDays(1).atStartOfDay(zone).toInstant();
    var now = clock.instant();
    var found = bookings.findInRoomsBetween(allRooms.stream().map(Room::getId).toList(), from, to);
    var booked = found.stream().map(Booking::getRoomId).collect(Collectors.toSet());
    var blackoutsByFacility =
        byId.keySet().stream()
            .collect(
                Collectors.toMap(
                    Function.identity(), id -> blackouts.findOverlapping(id, from, to)));
    var inputs =
        allRooms.stream()
            .filter(r -> r.isActive() || booked.contains(r.getId()))
            .sorted(
                Comparator.comparing((Room r) -> byId.get(r.getFacilityId()).getName())
                    .thenComparing(Room::getName))
            .map(r -> input(r, byId.get(r.getFacilityId()), blackoutsByFacility))
            .toList();
    return new Inputs(inputs, found.stream().map(b -> fact(b, now)).toList());
  }

  private static RoomInput input(
      Room room, Facility facility, Map<UUID, List<Blackout>> blackoutsByFacility) {
    var closed =
        blackoutsByFacility.get(facility.getId()).stream()
            .filter(b -> b.getRoomId() == null || b.getRoomId().equals(room.getId()))
            .map(b -> new Interval(b.getStartAt(), b.getEndAt()))
            .toList();
    return new RoomInput(
        room.getId(),
        room.getName(),
        facility.getId(),
        facility.getName(),
        ZoneId.of(facility.getTimeZone()),
        facility.getOpeningHours(),
        closed);
  }

  /** A booking's outcome as things stand now, written down or not (ADR-0004). */
  private BookingFact fact(Booking booking, Instant now) {
    var outcome =
        switch (booking.effectiveStatus(now, policy)) {
          case CONFIRMED -> Outcome.UPCOMING;
          case CHECKED_IN, COMPLETED -> Outcome.CHECKED_IN;
          case NO_SHOW -> Outcome.NO_SHOW;
          case CANCELLED -> Outcome.CANCELLED;
        };
    return new BookingFact(
        booking.getRoomId(),
        booking.getStartAt(),
        booking.getEndAt(),
        outcome,
        booking.isLateCancellation());
  }

  /** What the report is worked out from. */
  record Inputs(List<RoomInput> rooms, List<BookingFact> bookings) {}
}
