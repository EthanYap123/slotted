package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.BookingRules;
import dev.ethanyap.slotted.booking.domain.WaitlistEntry;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository.SettledNoShow;
import dev.ethanyap.slotted.booking.infrastructure.WaitlistRepository;
import dev.ethanyap.slotted.booking.infrastructure.WaitlistRepository.ExpiredEntry;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.facility.domain.Facility;
import dev.ethanyap.slotted.room.domain.Room;
import dev.ethanyap.slotted.user.domain.User;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Promotes waiting students into freed time (PRD BR-26, BR-27). Callers must hold the room's lock.
 *
 * <p>Promotion holds the room when it reaches each student, the reverse of the order a booking
 * takes them in (ADR-0006), so it never waits for a student: it tries the lock and moves on if
 * someone is mid-request (ADR-0007). A skipped entry stays waiting and is tried again at the next
 * write to the room.
 */
@Component
public class WaitlistQueue {

  private static final Logger log = LoggerFactory.getLogger(WaitlistQueue.class);

  private final WaitlistRepository waitlist;
  private final BookingRepository bookings;
  private final BookingLookup lookup;
  private final BookerFactsReader facts;
  private final BookingRecorder recorder;
  private final BookingPolicy policy;
  private final Clock clock;

  WaitlistQueue(
      WaitlistRepository waitlist,
      BookingRepository bookings,
      BookingLookup lookup,
      BookerFactsReader facts,
      BookingRecorder recorder,
      BookingPolicy policy,
      Clock clock) {
    this.waitlist = waitlist;
    this.bookings = bookings;
    this.lookup = lookup;
    this.facts = facts;
    this.recorder = recorder;
    this.policy = policy;
    this.clock = clock;
  }

  /**
   * Records overdue no-shows in a room and promotes into the freed time, in a transaction of its
   * own. Runs before a booking is attempted: if that attempt then failed in the same transaction,
   * the rollback would undo the promotion it had just given the waiting student (ADR-0007).
   */
  @Transactional
  public Settled settleAndPromote(UUID roomId) {
    var room = lookup.lockRoom(roomId);
    var now = clock.instant();
    var noShows = settle(roomId, now);
    return new Settled(noShows, promoteIn(room, now));
  }

  /** How many no-shows were written down and how many entries were promoted. */
  public record Settled(int noShows, int promoted) {}

  /**
   * Writes down that waiting entries whose slot has started are expired (BR-28). They already count
   * as expired everywhere (ADR-0004); this only records it. Returns how many.
   */
  @Transactional
  public int expireStarted() {
    var expired = waitlist.expireStarted(clock.instant());
    recorder.expired(
        expired.stream()
            .collect(
                Collectors.groupingBy(
                    ExpiredEntry::getRoomId,
                    Collectors.mapping(ExpiredEntry::getId, Collectors.toList()))));
    return expired.size();
  }

  /**
   * Writes down the no-shows that time has already made true in this room (ADR-0004). Callers must
   * hold the room's lock. Returns how many.
   */
  int settle(UUID roomId, Instant now) {
    var settled =
        bookings.settleNoShows(
            roomId, now.minus(policy.noShowGrace()), policy.noShowGrace().toSeconds());
    if (settled.isEmpty()) {
      return 0;
    }
    recorder.noShows(roomId, settled.stream().map(SettledNoShow::getId).toList());
    noticeSuspensions(settled.stream().map(SettledNoShow::getUserId).toList(), now);
    return settled.size();
  }

  /** Whether a booking holds some of this time in the room, so it can be waited for. */
  boolean isTaken(UUID roomId, Instant startAt, Instant endAt, Instant now) {
    return bookings.roomHasBookingBetween(roomId, startAt, endAt, now.minus(policy.noShowGrace()));
  }

  /**
   * Emails students whom these no-shows suspended (BR-18). Only once per suspension, however many
   * times it is noticed: the email is keyed by the student and the suspension's end.
   */
  private void noticeSuspensions(List<UUID> students, Instant now) {
    var users = lookup.users(students);
    var zone = ZoneId.of(Facility.DEFAULT_TIME_ZONE);
    facts
        .suspendedUntil(users, now)
        .forEach(
            (userId, until) ->
                users.stream()
                    .filter(u -> u.getId().equals(userId))
                    .findFirst()
                    .ifPresent(u -> recorder.suspended(u, until, zone)));
  }

  /**
   * Goes through the room's queue in order, promoting every entry that can have its slot now.
   * Returns how many were promoted.
   */
  int promoteIn(Room room, Instant now) {
    var queue = waitlist.findQueue(room.getId(), now);
    if (queue.isEmpty()) {
      return 0;
    }
    var promoted = 0;
    var facility = lookup.facilityOf(room);
    for (var entry : queue) {
      if (!isFree(entry, now)) {
        continue;
      }
      var student = lookup.tryLockActiveUser(entry.getUserId());
      if (student.isEmpty()) {
        log.info("Waitlist entry {} skipped: its student is busy or inactive", entry.getId());
        continue;
      }
      if (isEligible(entry, room, facility, student.get(), now)) {
        var booking =
            BookingService.insert(
                bookings,
                Booking.promoted(
                    room.getId(), entry.getUserId(), entry.getStartAt(), entry.getEndAt()));
        recorder.promoted(entry, booking, now);
        promoted++;
      }
    }
    return promoted;
  }

  /** Entries that a change to these rooms and times would affect. */
  List<WaitlistEntry> waitingBetween(
      Collection<Room> rooms, Instant from, Instant to, Instant now) {
    return rooms.isEmpty()
        ? List.of()
        : waitlist.findWaitingInRoomsBetween(ids(rooms), from, to, now);
  }

  List<WaitlistEntry> waitingIn(Collection<Room> rooms, Instant now) {
    return rooms.isEmpty() ? List.of() : waitlist.findWaitingInRooms(ids(rooms), now);
  }

  private boolean isFree(WaitlistEntry entry, Instant now) {
    return !isTaken(entry.getRoomId(), entry.getStartAt(), entry.getEndAt(), now);
  }

  /** The student's quotas and the slot's rules, checked again as they are now (BR-27). */
  private boolean isEligible(
      WaitlistEntry entry, Room room, Facility facility, User student, Instant now) {
    var start = entry.getStartAt();
    var end = entry.getEndAt();
    try {
      BookingRules.check(
          new BookingRules.Request(start, end, Booking.PROMOTED_ATTENDEES),
          lookup.roomFacts(room, facility, start, end),
          facts.factsFor(
              student,
              start,
              end,
              facts.upcomingBookings(student.getId(), now),
              ZoneId.of(facility.getTimeZone()),
              now),
          now,
          policy);
      return true;
    } catch (ApiException stillWaiting) {
      log.debug("Waitlist entry {} stays waiting: {}", entry.getId(), stillWaiting.code());
      return false;
    }
  }

  private static List<UUID> ids(Collection<Room> rooms) {
    return rooms.stream().map(Room::getId).toList();
  }
}
