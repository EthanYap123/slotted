package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.user.application.Suspensions;
import dev.ethanyap.slotted.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Lets the user feature show suspensions without depending on bookings. */
@Component
public class SuspensionsFromBookings implements Suspensions {

  private final BookerFactsReader facts;
  private final UserRepository users;
  private final Clock clock;

  SuspensionsFromBookings(BookerFactsReader facts, UserRepository users, Clock clock) {
    this.facts = facts;
    this.users = users;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, Instant> suspendedUntil(Collection<UUID> userIds) {
    return facts.suspendedUntil(users.findAllById(userIds), clock.instant());
  }
}
