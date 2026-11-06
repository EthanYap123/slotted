package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.Blackout;
import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.BookingRules;
import dev.ethanyap.slotted.booking.infrastructure.BlackoutRepository;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.facility.application.FacilityService;
import dev.ethanyap.slotted.facility.domain.Facility;
import dev.ethanyap.slotted.facility.domain.OpeningPeriod;
import dev.ethanyap.slotted.room.domain.Room;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes to when a facility can be used: blackouts and opening hours (BR-30, BR-31). Both cancel
 * the bookings they rule out. Access is checked by callers.
 *
 * <p>Each change first locks the facility's rooms, the same lock a new booking takes (ADR-0006), so
 * a booking in progress finishes before the change looks for affected bookings, and a later one
 * sees the change. Either way no active booking is left inside a blackout or outside opening hours.
 */
@Service
public class ScheduleService {

  private static final Instant FAR_PAST = Instant.EPOCH;
  private static final Instant FAR_FUTURE = Instant.parse("2100-01-01T00:00:00Z");
  private static final String HOURS_CHANGED = "The facility's opening hours changed.";

  private final BlackoutRepository blackouts;
  private final BookingRepository bookings;
  private final BookingLookup lookup;
  private final FacilityService facilities;
  private final BookingPolicy policy;
  private final Clock clock;

  ScheduleService(
      BlackoutRepository blackouts,
      BookingRepository bookings,
      BookingLookup lookup,
      FacilityService facilities,
      BookingPolicy policy,
      Clock clock) {
    this.blackouts = blackouts;
    this.bookings = bookings;
    this.lookup = lookup;
    this.facilities = facilities;
    this.policy = policy;
    this.clock = clock;
  }

  /** Replaces the weekly hours, cancelling future bookings that no longer fit. */
  @Transactional
  public Impact changeOpeningHours(
      UUID actorId, UUID facilityId, Collection<OpeningPeriod> periods, boolean dryRun) {
    Facility.checkOneEntryPerDay(periods);
    var facility = facilities.get(facilityId);
    var rooms = lookup.lockRoomsOf(facilityId);
    var now = clock.instant();
    var zone = ZoneId.of(facility.getTimeZone());
    var affected =
        counting(rooms, now, FAR_FUTURE, now).stream()
            .filter(b -> b.getStartAt().isAfter(now))
            .filter(b -> !fits(b, periods, zone))
            .toList();
    var views = lookup.viewsOf(affected, now, true);
    if (!dryRun) {
      affected.forEach(b -> b.cancelByStaff(actorId, now, policy, HOURS_CHANGED));
      facility.replaceOpeningHours(periods);
    }
    return new Impact(!dryRun, views);
  }

  /** The result of a dry run, or the blackout that was created. */
  public record BlackoutOutcome(Blackout created, Impact impact) {}

  /** Creates a blackout, cancelling the active bookings inside it (unless this is a dry run). */
  @Transactional
  public BlackoutOutcome createBlackout(
      UUID actorId, UUID facilityId, NewBlackout request, boolean dryRun) {
    facilities.get(facilityId);
    var rooms = lookup.lockRoomsOf(facilityId);
    var now = clock.instant();
    if (request.roomId() != null
        && rooms.stream().noneMatch(r -> r.getId().equals(request.roomId()))) {
      throw new ApiException(ErrorCode.NOT_FOUND, "This facility has no room with this ID.");
    }
    var blackout =
        new Blackout(
            facilityId,
            request.roomId(),
            request.startAt(),
            request.endAt(),
            request.reason(),
            actorId);
    blackout.checkNewAt(now);
    var inScope =
        rooms.stream()
            .filter(r -> request.roomId() == null || r.getId().equals(request.roomId()))
            .toList();
    var affected =
        counting(inScope, request.startAt(), request.endAt(), now).stream()
            .filter(b -> b.effectiveStatus(now, policy).blocksRoom())
            .toList();
    var views = lookup.viewsOf(affected, now, true);
    if (dryRun) {
      return new BlackoutOutcome(null, new Impact(false, views));
    }
    affected.forEach(b -> b.cancelByStaff(actorId, now, policy, blackout.getReason()));
    return new BlackoutOutcome(blackouts.save(blackout), new Impact(true, views));
  }

  /** Blackouts overlapping a period; either end may be left open. */
  @Transactional(readOnly = true)
  public List<Blackout> listBlackouts(UUID facilityId, Instant from, Instant to) {
    facilities.get(facilityId);
    return blackouts.findOverlapping(
        facilityId, from == null ? FAR_PAST : from, to == null ? FAR_FUTURE : to);
  }

  /** Removes a blackout that has not ended. Bookings it cancelled stay cancelled (MG-3). */
  @Transactional
  public void deleteBlackout(UUID blackoutId) {
    var blackout =
        blackouts
            .findById(blackoutId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No blackout with this ID."));
    if (!blackout.getEndAt().isAfter(clock.instant())) {
      throw new ApiException(ErrorCode.BLACKOUT_ENDED, "A past blackout cannot be removed.");
    }
    blackouts.delete(blackout);
  }

  /** The facility a blackout belongs to, for access checks; null if it does not exist. */
  @Transactional(readOnly = true)
  public UUID facilityOfBlackout(UUID blackoutId) {
    return blackouts.findById(blackoutId).map(Blackout::getFacilityId).orElse(null);
  }

  private List<Booking> counting(List<Room> rooms, Instant from, Instant to, Instant now) {
    if (rooms.isEmpty()) {
      return List.of();
    }
    return bookings.findCountingInRoomsBetween(
        rooms.stream().map(Room::getId).toList(), from, to, now.minus(policy.noShowGrace()));
  }

  private static boolean fits(Booking booking, Collection<OpeningPeriod> periods, ZoneId zone) {
    var start = booking.getStartAt().atZone(zone);
    var end = booking.getEndAt().atZone(zone);
    var hours = periods.stream().filter(p -> p.dayOfWeek() == start.getDayOfWeek()).findFirst();
    return BookingRules.withinOpeningHours(start, end, hours);
  }

  /** A blackout to create; {@code roomId} is null for the whole facility. */
  public record NewBlackout(UUID roomId, Instant startAt, Instant endAt, String reason) {}

  /** What a change does: whether it was applied, and the bookings it cancels. */
  public record Impact(boolean applied, List<BookingView> affectedBookings) {}
}
