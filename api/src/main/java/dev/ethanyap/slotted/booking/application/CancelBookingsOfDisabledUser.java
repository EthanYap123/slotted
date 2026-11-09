package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.booking.infrastructure.WaitlistRepository;
import dev.ethanyap.slotted.user.domain.UserAccessChanged;
import dev.ethanyap.slotted.user.domain.UserStatus;
import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Disabling an account cancels its future bookings and waitlist entries, in the same transaction,
 * so the rooms free up for others, who are promoted straight away (PRD AD-1, BR-26). Bookings
 * already under way are left to finish.
 */
@Component
public class CancelBookingsOfDisabledUser {

  private static final String REASON = "The account was disabled.";

  private final BookingRepository bookings;
  private final WaitlistRepository waitlist;
  private final BookingNotifier notifier;
  private final BookingLookup lookup;
  private final WaitlistQueue queue;
  private final BookingPolicy policy;
  private final Clock clock;

  CancelBookingsOfDisabledUser(
      BookingRepository bookings,
      WaitlistRepository waitlist,
      BookingNotifier notifier,
      BookingLookup lookup,
      WaitlistQueue queue,
      BookingPolicy policy,
      Clock clock) {
    this.bookings = bookings;
    this.waitlist = waitlist;
    this.notifier = notifier;
    this.lookup = lookup;
    this.queue = queue;
    this.policy = policy;
    this.clock = clock;
  }

  @EventListener
  public void on(UserAccessChanged event) {
    // Locking first keeps the usual order of locks: user, then rooms (ADR-0006).
    var disabled =
        lookup.lockUser(event.userId()).filter(u -> u.getStatus() == UserStatus.DISABLED);
    if (disabled.isEmpty()) {
      return;
    }
    var now = clock.instant();
    waitlist.findWaitingByUser(event.userId(), now).forEach(entry -> entry.markCancelled(now));
    var upcoming = bookings.findUpcoming(event.userId(), now);
    // In ID order, so two callers cannot lock the same rooms in opposite orders.
    var rooms =
        upcoming.stream()
            .map(Booking::getRoomId)
            .distinct()
            .sorted()
            .map(lookup::lockRoom)
            .toList();
    upcoming.forEach(booking -> booking.cancelBySystem(now, policy, REASON));
    notifier.cancelled(upcoming, REASON);
    rooms.forEach(room -> queue.promoteIn(room, now));
  }
}
