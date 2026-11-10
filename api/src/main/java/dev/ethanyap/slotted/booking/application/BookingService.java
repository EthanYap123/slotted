package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.BookingRules;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Making bookings (PRD ST-3, BR-1 to BR-11). Reading them is in {@link BookingQueries}. */
@Service
public class BookingService {

  /** SQLSTATE Postgres uses when an exclusion constraint rejects a row. */
  private static final String EXCLUSION_VIOLATION = "23P01";

  private final BookingRepository bookings;
  private final BookingLookup lookup;
  private final BookerFactsReader facts;
  private final BookingRecorder recorder;
  private final WaitlistQueue queue;
  private final BookingPolicy policy;
  private final Clock clock;

  BookingService(
      BookingRepository bookings,
      BookingLookup lookup,
      BookerFactsReader facts,
      BookingRecorder recorder,
      WaitlistQueue queue,
      BookingPolicy policy,
      Clock clock) {
    this.bookings = bookings;
    this.lookup = lookup;
    this.facts = facts;
    this.recorder = recorder;
    this.queue = queue;
    this.policy = policy;
    this.clock = clock;
  }

  /**
   * Books a slot. In one transaction: lock the booker's row (quotas), then the room (bookings for
   * one room take turns, ADR-0006), settle overdue no-shows in the room (ADR-0004), check every
   * rule, then insert. The exclusion constraint still guarantees no overlap whatever writes the row
   * (ADR-0001); a violation becomes {@code SLOT_TAKEN}. Locks are always taken in this order, so
   * booking transactions cannot deadlock on each other.
   */
  @Transactional
  public BookingView create(UUID userId, NewBooking request) {
    var user = lookup.lockActiveUser(userId);
    var room = lookup.lockRoom(request.roomId());
    var facility = lookup.facilityOf(room);
    var now = clock.instant();
    queue.settle(room.getId(), now);

    var zone = ZoneId.of(facility.getTimeZone());
    BookingRules.check(
        new BookingRules.Request(request.startAt(), request.endAt(), request.attendees()),
        lookup.roomFacts(room, facility, request.startAt(), request.endAt()),
        facts.factsFor(
            user,
            request.startAt(),
            request.endAt(),
            facts.upcomingBookings(user.getId(), now),
            zone,
            now),
        now,
        policy);

    if (queue.isTaken(room.getId(), request.startAt(), request.endAt(), now)) {
      throw slotTaken(
          facts.canWaitFor(user, room.getId(), request.startAt(), request.endAt(), now));
    }
    var booking =
        new Booking(
            room.getId(),
            user.getId(),
            request.startAt(),
            request.endAt(),
            request.attendees(),
            request.purpose());
    var saved = insert(bookings, booking);
    recorder.created(user.getId(), saved);
    return lookup.view(saved, room, facility, now, null);
  }

  /**
   * Inserts a booking. The exclusion constraint has the last word (ADR-0001): if it refuses the
   * row, the slot was taken.
   */
  static Booking insert(BookingRepository bookings, Booking booking) {
    try {
      return bookings.saveAndFlush(booking);
    } catch (DataIntegrityViolationException e) {
      if (isExclusionViolation(e)) {
        throw slotTaken();
      }
      throw e;
    }
  }

  private static boolean isExclusionViolation(Throwable e) {
    for (var cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  static ApiException slotTaken() {
    return new ApiException(
        ErrorCode.SLOT_TAKEN, "Someone else has booked this room for some or all of that time.");
  }

  /** {@code SLOT_TAKEN}, saying whether the caller could join the waitlist instead (BR-24). */
  private static ApiException slotTaken(boolean waitlistable) {
    return new ApiException(
        ErrorCode.SLOT_TAKEN,
        "Someone else has booked this room for some or all of that time.",
        Map.of("waitlistable", waitlistable));
  }

  /** What the caller asked to book. */
  public record NewBooking(
      UUID roomId, Instant startAt, Instant endAt, int attendees, String purpose) {}
}
