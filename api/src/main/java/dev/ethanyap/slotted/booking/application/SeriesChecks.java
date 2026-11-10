package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.application.SeriesService.NewSeries;
import dev.ethanyap.slotted.booking.application.SeriesService.Occurrence;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.BookingRules;
import dev.ethanyap.slotted.booking.domain.BookingSeries.Frequency;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.facility.domain.Facility;
import dev.ethanyap.slotted.room.domain.Room;
import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.domain.User;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The rules a recurring booking is checked against (BR-20 to BR-22): those about the series as a
 * whole, and each date's own booking rules. Used both by the preview and when creating the series.
 */
@Component
class SeriesChecks {

  private final BookerFactsReader facts;
  private final BookingLookup lookup;
  private final WaitlistQueue queue;
  private final BookingPolicy policy;

  SeriesChecks(
      BookerFactsReader facts, BookingLookup lookup, WaitlistQueue queue, BookingPolicy policy) {
    this.facts = facts;
    this.lookup = lookup;
    this.queue = queue;
    this.policy = policy;
  }

  /** The rules about the series itself, which fail it as a whole (BR-20, BR-21). */
  void checkSeriesAsAWhole(User user, NewSeries request, Facility facility, Instant now) {
    if (request.count() > policy.maxSeriesOccurrences()) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED,
          "A series can have at most " + policy.maxSeriesOccurrences() + " dates.");
    }
    var starts = startTimes(request, facility);
    if (starts.getLast().isAfter(now.plus(policy.seriesHorizon()))) {
      throw new ApiException(
          ErrorCode.SERIES_TOO_FAR,
          "The last date must be within " + policy.seriesHorizon().toDays() + " days.");
    }
    if (user.getRole() != Role.STUDENT) {
      return;
    }
    if (request.frequency() == Frequency.DAILY) {
      throw new ApiException(ErrorCode.SERIES_LIMIT, "Students can only repeat bookings weekly.");
    }
    if (facts.activeSeries(user.getId(), now) >= policy.maxActiveSeries()) {
      throw new ApiException(
          ErrorCode.SERIES_LIMIT,
          "You already have a repeating booking. Cancel it to make another.");
    }
    if (facts.upcomingBookings(user.getId(), now) >= policy.maxUpcoming()) {
      throw new ApiException(
          ErrorCode.QUOTA_UPCOMING_EXCEEDED,
          "You can hold at most " + policy.maxUpcoming() + " upcoming bookings.");
    }
  }

  List<Occurrence> checkOccurrences(
      User user, Room room, Facility facility, NewSeries request, Instant now) {
    var zone = ZoneId.of(facility.getTimeZone());
    var length = Duration.between(request.firstStartAt(), request.firstEndAt());
    var results = new ArrayList<Occurrence>();
    for (var start : startTimes(request, facility)) {
      var end = start.plus(length);
      try {
        BookingRules.check(
            new BookingRules.Request(start, end, request.attendees()),
            lookup.roomFacts(room, facility, start, end),
            facts.factsFor(user, start, end, 0, zone, now),
            now,
            policy.forSeries());
        if (queue.isTaken(room.getId(), start, end, now)) {
          throw BookingService.slotTaken();
        }
        results.add(new Occurrence(start, end, true, null, null));
      } catch (ApiException problem) {
        results.add(new Occurrence(start, end, false, problem.code(), problem.getMessage()));
      }
    }
    return results;
  }

  /** Start times, stepped in the facility's zone so the local time stays the same. */
  private static List<Instant> startTimes(NewSeries request, Facility facility) {
    var first = request.firstStartAt().atZone(ZoneId.of(facility.getTimeZone()));
    var starts = new ArrayList<Instant>();
    for (var i = 0; i < request.count(); i++) {
      starts.add(
          (request.frequency() == Frequency.DAILY ? first.plusDays(i) : first.plusWeeks(i))
              .toInstant());
    }
    return starts;
  }
}
