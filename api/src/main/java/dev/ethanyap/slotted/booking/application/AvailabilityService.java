package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.infrastructure.BlackoutRepository;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.facility.application.FacilityAccess;
import dev.ethanyap.slotted.facility.infrastructure.FacilityRepository;
import dev.ethanyap.slotted.room.domain.Room;
import dev.ethanyap.slotted.room.domain.RoomType;
import dev.ethanyap.slotted.room.infrastructure.RoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The day timeline for a facility (PRD ST-2): every active room as 30-minute cells. Status is
 * evaluated at request time, so a booking that just became a no-show is already free (ADR-0004).
 */
@Service
public class AvailabilityService {

  private static final Duration CELL = Duration.ofMinutes(30);

  private final FacilityRepository facilities;
  private final RoomRepository rooms;
  private final BookingRepository bookings;
  private final BlackoutRepository blackouts;
  private final FacilityAccess access;
  private final BookingPolicy policy;
  private final Clock clock;

  public AvailabilityService(
      FacilityRepository facilities,
      RoomRepository rooms,
      BookingRepository bookings,
      BlackoutRepository blackouts,
      FacilityAccess access,
      BookingPolicy policy,
      Clock clock) {
    this.facilities = facilities;
    this.rooms = rooms;
    this.bookings = bookings;
    this.blackouts = blackouts;
    this.access = access;
    this.policy = policy;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public DayAvailability forDay(UUID viewerId, UUID facilityId, LocalDate date, RoomType type) {
    var facility =
        facilities
            .findById(facilityId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No facility with this ID."));
    var zone = ZoneId.of(facility.getTimeZone());
    var hours =
        facility.getOpeningHours().stream()
            .filter(p -> p.dayOfWeek() == date.getDayOfWeek())
            .findFirst();
    var activeRooms =
        rooms.findByFacilityIdOrderByName(facilityId).stream()
            .filter(Room::isActive)
            .filter(r -> type == null || r.getType() == type)
            .toList();
    if (hours.isEmpty()) {
      return new DayAvailability(
          date, null, null, activeRooms.stream().map(r -> new RoomDay(r, List.of())).toList());
    }

    var opensAt = date.atTime(hours.get().opensAt()).atZone(zone).toInstant();
    var closesAt = date.atTime(hours.get().closesAt()).atZone(zone).toInstant();
    var now = clock.instant();
    var staff = access.canManage(facilityId, viewerId);
    var dayBookings =
        activeRooms.isEmpty()
            ? List.<Booking>of()
            : bookings.findCountingInRoomsBetween(
                activeRooms.stream().map(Room::getId).toList(),
                opensAt,
                closesAt,
                now.minus(policy.noShowGrace()));

    var dayBlackouts = blackouts.findOverlapping(facilityId, opensAt, closesAt);

    var roomDays = new ArrayList<RoomDay>();
    for (var room : activeRooms) {
      var roomBookings =
          dayBookings.stream().filter(b -> b.getRoomId().equals(room.getId())).toList();
      var cells = new ArrayList<Cell>();
      for (var start = opensAt; start.isBefore(closesAt); start = start.plus(CELL)) {
        var cell = cell(start, roomBookings, viewerId, staff, now);
        var from = start;
        var blackedOut =
            dayBlackouts.stream().anyMatch(b -> b.covers(room.getId(), from, from.plus(CELL)));
        cells.add(
            blackedOut && cell.state() != CellState.MINE && cell.state() != CellState.BOOKED
                ? new Cell(start, CellState.BLACKOUT, null)
                : cell);
      }
      roomDays.add(new RoomDay(room, cells));
    }
    return new DayAvailability(date, opensAt, closesAt, roomDays);
  }

  private static Cell cell(
      Instant start, List<Booking> roomBookings, UUID viewerId, boolean staff, Instant now) {
    var end = start.plus(CELL);
    var booking =
        roomBookings.stream()
            .filter(b -> b.getStartAt().isBefore(end) && b.getEndAt().isAfter(start))
            .findFirst();
    if (booking.isPresent()) {
      var mine = booking.get().getUserId().equals(viewerId);
      var id = mine || staff ? booking.get().getId() : null;
      return new Cell(start, mine ? CellState.MINE : CellState.BOOKED, id);
    }
    return new Cell(start, start.isBefore(now) ? CellState.PAST : CellState.FREE, null);
  }

  /** One day of a facility; opening and closing are null when it is closed. */
  public record DayAvailability(
      LocalDate date, Instant opensAt, Instant closesAt, List<RoomDay> rooms) {}

  /** One room's cells for the day. */
  public record RoomDay(Room room, List<Cell> cells) {}

  /**
   * One 30-minute cell. The booking ID is only given for the viewer's own bookings, or to staff.
   */
  public record Cell(Instant startAt, CellState state, UUID bookingId) {}

  /** What a cell shows. */
  public enum CellState {
    FREE,
    BOOKED,
    MINE,
    BLACKOUT,
    PAST
  }
}
