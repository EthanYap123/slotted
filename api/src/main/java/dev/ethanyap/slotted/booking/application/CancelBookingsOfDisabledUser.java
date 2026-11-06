package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.user.domain.UserAccessChanged;
import dev.ethanyap.slotted.user.domain.UserStatus;
import dev.ethanyap.slotted.user.infrastructure.UserRepository;
import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Disabling an account cancels its future bookings, in the same transaction, so the rooms free up
 * for others (PRD AD-1). Bookings already under way are left to finish.
 */
@Component
public class CancelBookingsOfDisabledUser {

  private static final String REASON = "The account was disabled.";

  private final BookingRepository bookings;
  private final UserRepository users;
  private final BookingPolicy policy;
  private final Clock clock;

  public CancelBookingsOfDisabledUser(
      BookingRepository bookings, UserRepository users, BookingPolicy policy, Clock clock) {
    this.bookings = bookings;
    this.users = users;
    this.policy = policy;
    this.clock = clock;
  }

  @EventListener
  public void on(UserAccessChanged event) {
    var disabled = users.findById(event.userId()).filter(u -> u.getStatus() == UserStatus.DISABLED);
    if (disabled.isEmpty()) {
      return;
    }
    var now = clock.instant();
    bookings
        .findUpcoming(event.userId(), now)
        .forEach(booking -> booking.cancelBySystem(now, policy, REASON));
  }
}
