package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.BookingRules.BookerFacts;
import dev.ethanyap.slotted.booking.domain.Suspension;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.domain.User;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Reads what the booking rules need to know about the person booking. */
@Component
class BookerFactsReader {

  private final BookingRepository bookings;
  private final BookingPolicy policy;

  BookerFactsReader(BookingRepository bookings, BookingPolicy policy) {
    this.bookings = bookings;
    this.policy = policy;
  }

  /** A series counts as one booking toward the upcoming limit (BR-21). */
  long upcomingBookings(UUID userId, Instant now) {
    return bookings.countUpcomingSingles(userId, now) + activeSeries(userId, now);
  }

  long activeSeries(UUID userId, Instant now) {
    return bookings.countUpcomingSeries(userId, now);
  }

  /**
   * The facts for a booking of {@code [start, end)}. Quotas only apply to students.
   *
   * @param upcoming how many upcoming bookings to report; series occurrences pass 0 because the
   *     series as a whole is checked once
   */
  BookerFacts factsFor(
      User user, Instant start, Instant end, long upcoming, ZoneId zone, Instant now) {
    if (user.getRole() != Role.STUDENT) {
      return new BookerFacts(false, 0, 0, false, Optional.empty());
    }
    var cutoff = now.minus(policy.noShowGrace());
    var day = start.atZone(zone).toLocalDate();
    var dayStart = day.atStartOfDay(zone).toInstant();
    var dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
    var minutesThatDay =
        bookings.findCountingStartingBetween(user.getId(), dayStart, dayEnd, cutoff).stream()
            .mapToLong(b -> Duration.between(b.getStartAt(), b.getEndAt()).toMinutes())
            .sum();
    return new BookerFacts(
        true,
        upcoming,
        minutesThatDay,
        bookings.userHasBookingBetween(user.getId(), start, end, cutoff),
        suspendedUntil(List.of(user), now).values().stream().findFirst());
  }

  /** Ends of suspensions in force for these users (BR-18). */
  Map<UUID, Instant> suspendedUntil(Collection<User> forUsers, Instant now) {
    if (forUsers.isEmpty()) {
      return Map.of();
    }
    var cutoff = now.minus(policy.noShowGrace());
    var since = now.minus(policy.noShowPeriod()).minus(policy.noShowGrace());
    var byUser =
        bookings.findNoShows(forUsers.stream().map(User::getId).toList(), since, cutoff).stream()
            .collect(
                Collectors.groupingBy(
                    BookingRepository.NoShow::getUserId,
                    Collectors.mapping(
                        n -> n.getStartAt().plus(policy.noShowGrace()), Collectors.toList())));
    return forUsers.stream()
        .flatMap(
            u ->
                Suspension.until(
                    byUser.getOrDefault(u.getId(), List.of()),
                    u.getSuspensionClearedAt(),
                    now,
                    policy)
                    .map(until -> Map.entry(u.getId(), until))
                    .stream())
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }
}
