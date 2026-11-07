package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.BookingRules;
import dev.ethanyap.slotted.booking.domain.WaitlistEntry;
import dev.ethanyap.slotted.booking.domain.WaitlistStatus;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.booking.infrastructure.WaitlistRepository;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.user.domain.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Joining, listing and leaving waitlists (PRD ST-7, BR-24, BR-25, BR-28). */
@Service
public class WaitlistService {

  private final WaitlistRepository waitlist;
  private final BookingRepository bookings;
  private final BookingLookup lookup;
  private final BookerFactsReader facts;
  private final WaitlistQueue queue;
  private final BookingPolicy policy;
  private final Clock clock;

  WaitlistService(
      WaitlistRepository waitlist,
      BookingRepository bookings,
      BookingLookup lookup,
      BookerFactsReader facts,
      WaitlistQueue queue,
      BookingPolicy policy,
      Clock clock) {
    this.waitlist = waitlist;
    this.bookings = bookings;
    this.lookup = lookup;
    this.facts = facts;
    this.queue = queue;
    this.policy = policy;
    this.clock = clock;
  }

  /**
   * Joins the queue for a slot that is taken. The slot must pass every booking rule except that it
   * is free, under the same locks as a booking (ADR-0006).
   */
  @Transactional
  public WaitlistView join(UUID userId, NewEntry request) {
    var user = lookup.lockActiveUser(userId);
    if (user.getRole() != Role.STUDENT) {
      throw new ApiException(ErrorCode.FORBIDDEN, "Only students can join a waitlist.");
    }
    var room = lookup.lockRoom(request.roomId());
    var facility = lookup.facilityOf(room);
    var now = clock.instant();
    queue.settle(room.getId(), now);

    var start = request.startAt();
    var end = request.endAt();
    BookingRules.check(
        new BookingRules.Request(start, end, Booking.PROMOTED_ATTENDEES),
        lookup.roomFacts(room, facility, start, end),
        facts.factsFor(
            user,
            start,
            end,
            facts.upcomingBookings(userId, now),
            ZoneId.of(facility.getTimeZone()),
            now),
        now,
        policy);
    if (!bookings.roomHasBookingBetween(
        room.getId(), start, end, now.minus(policy.noShowGrace()))) {
      throw new ApiException(ErrorCode.SLOT_AVAILABLE, "This slot is free, so you can book it.");
    }
    if (waitlist.isWaitingFor(userId, room.getId(), start, end, now)) {
      throw new ApiException(ErrorCode.ALREADY_WAITING, "You are already waiting for this slot.");
    }
    if (waitlist.countWaiting(userId, now) >= policy.maxWaiting()) {
      throw new ApiException(
          ErrorCode.WAITLIST_LIMIT,
          "You can wait for at most " + policy.maxWaiting() + " slots at a time.");
    }
    // The database keeps microseconds, so this matches what later queries compare it with.
    var joinedAt = now.truncatedTo(ChronoUnit.MICROS);
    var entry =
        waitlist.saveAndFlush(new WaitlistEntry(room.getId(), userId, start, end, joinedAt));
    return viewOf(entry, room.getName(), now);
  }

  /** The caller's entries: those still waiting, soonest first, then the rest, latest first. */
  @Transactional(readOnly = true)
  public List<WaitlistView> mine(UUID userId) {
    var now = clock.instant();
    var entries = waitlist.findTop50ByUserIdOrderByStartAtDesc(userId);
    var names = lookup.roomNames(entries.stream().map(WaitlistEntry::getRoomId).toList());
    var waiting =
        entries.stream()
            .filter(e -> e.isWaitingAt(now))
            .sorted(Comparator.comparing(WaitlistEntry::getStartAt));
    var others = entries.stream().filter(e -> !e.isWaitingAt(now));
    return Stream.concat(waiting, others)
        .map(e -> viewOf(e, names.get(e.getRoomId()), now))
        .toList();
  }

  /** Leaves a queue. Takes the room's lock first, so promotion cannot be picking this entry. */
  @Transactional
  public void leave(UUID userId, UUID entryId) {
    var roomId = waitlist.findRoomIdById(entryId).orElseThrow(WaitlistService::notFound);
    lookup.lockRoom(roomId);
    var now = clock.instant();
    var entry =
        waitlist
            .findById(entryId)
            .filter(e -> e.getUserId().equals(userId))
            .filter(e -> e.isWaitingAt(now))
            .orElseThrow(WaitlistService::notFound);
    entry.markCancelled(now);
  }

  private WaitlistView viewOf(WaitlistEntry entry, String roomName, Instant now) {
    var status = entry.effectiveStatus(now);
    Long position = null;
    if (status == WaitlistStatus.WAITING) {
      position =
          1
              + waitlist.countAhead(
                  entry.getRoomId(),
                  entry.getStartAt(),
                  entry.getEndAt(),
                  entry.getCreatedAt(),
                  entry.getId(),
                  now);
    }
    return new WaitlistView(entry, roomName, status, position);
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.NOT_FOUND, "No waiting entry with this ID.");
  }

  /** The slot the caller wants to wait for. */
  public record NewEntry(UUID roomId, Instant startAt, Instant endAt) {}

  /**
   * An entry with what it needs for display.
   *
   * @param position place in the queue, from 1; null unless still waiting
   */
  public record WaitlistView(
      WaitlistEntry entry, String roomName, WaitlistStatus status, Long position) {}
}
