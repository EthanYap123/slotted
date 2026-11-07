package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.BookingRules;
import dev.ethanyap.slotted.booking.domain.BookingSeries;
import dev.ethanyap.slotted.booking.domain.BookingSeries.Frequency;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.booking.infrastructure.BookingSeriesRepository;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.facility.application.FacilityAccess;
import dev.ethanyap.slotted.facility.domain.Facility;
import dev.ethanyap.slotted.room.domain.Room;
import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.domain.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recurring bookings (PRD ST-6, BR-20 to BR-23). Every occurrence is checked by the same rules as a
 * single booking, except the 14-day window, which is replaced by the series limit; the quota on
 * upcoming bookings is checked once, because a series counts as one.
 */
@Service
public class SeriesService {

  private final BookingSeriesRepository seriesRepository;
  private final BookingRepository bookings;
  private final BookerFactsReader facts;
  private final BookingLookup lookup;
  private final FacilityAccess access;
  private final BookingPolicy policy;
  private final Clock clock;

  SeriesService(
      BookingSeriesRepository seriesRepository,
      BookingRepository bookings,
      BookerFactsReader facts,
      BookingLookup lookup,
      FacilityAccess access,
      BookingPolicy policy,
      Clock clock) {
    this.seriesRepository = seriesRepository;
    this.bookings = bookings;
    this.facts = facts;
    this.lookup = lookup;
    this.access = access;
    this.policy = policy;
    this.clock = clock;
  }

  /** Says which occurrences could be booked right now. Saves nothing. */
  @Transactional(readOnly = true)
  public List<Occurrence> preview(UUID userId, NewSeries request) {
    var user = lookup.activeUser(userId);
    var room = lookup.room(request.roomId());
    var facility = lookup.facilityOf(room);
    var now = clock.instant();
    checkSeriesAsAWhole(user, request, facility, now);
    return checkOccurrences(user, room, facility, request, now);
  }

  /**
   * Creates the series with every occurrence that is still bookable; the preview may be out of
   * date. Takes the same locks as a single booking (ADR-0006), so the checks cannot be overtaken.
   */
  @Transactional
  public CreatedSeries create(UUID userId, NewSeries request) {
    var user = lookup.lockActiveUser(userId);
    var room = lookup.lockRoom(request.roomId());
    var facility = lookup.facilityOf(room);
    var now = clock.instant();
    checkSeriesAsAWhole(user, request, facility, now);
    bookings.settleNoShows(
        room.getId(), now.minus(policy.noShowGrace()), policy.noShowGrace().toSeconds());

    var occurrences = checkOccurrences(user, room, facility, request, now);
    if (occurrences.stream().noneMatch(Occurrence::bookable)) {
      throw new ApiException(
          ErrorCode.SERIES_NOTHING_BOOKABLE, "None of these dates can be booked.");
    }
    var series =
        seriesRepository.save(
            new BookingSeries(user.getId(), room.getId(), request.frequency(), request.count()));
    for (var occurrence : occurrences) {
      if (occurrence.bookable()) {
        BookingService.insert(
            bookings,
            Booking.occurrence(
                series.getId(),
                room.getId(),
                user.getId(),
                occurrence.startAt(),
                occurrence.endAt(),
                request.attendees(),
                request.purpose()));
      }
    }
    var skipped = occurrences.stream().filter(o -> !o.bookable()).toList();
    return new CreatedSeries(series, occurrencesOf(series, now, false), skipped);
  }

  /** A series the caller made, or one in a facility they manage. */
  @Transactional(readOnly = true)
  public CreatedSeries get(UUID actorId, UUID seriesId) {
    var series = visibleSeries(actorId, seriesId);
    var staff = !series.getUserId().equals(actorId);
    return new CreatedSeries(series, occurrencesOf(series, clock.instant(), staff), List.of());
  }

  /** The rules about the series itself, which fail it as a whole (BR-20, BR-21). */
  private void checkSeriesAsAWhole(User user, NewSeries request, Facility facility, Instant now) {
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

  private List<Occurrence> checkOccurrences(
      User user, Room room, Facility facility, NewSeries request, Instant now) {
    var zone = ZoneId.of(facility.getTimeZone());
    var length = Duration.between(request.firstStartAt(), request.firstEndAt());
    var cutoff = now.minus(policy.noShowGrace());
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
        if (bookings.roomHasBookingBetween(room.getId(), start, end, cutoff)) {
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

  BookingSeries visibleSeries(UUID actorId, UUID seriesId) {
    var series =
        seriesRepository
            .findById(seriesId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No series with this ID."));
    var room = lookup.room(series.getRoomId());
    if (!series.getUserId().equals(actorId) && !access.canManage(room.getFacilityId(), actorId)) {
      throw new ApiException(ErrorCode.NOT_FOUND, "No series with this ID.");
    }
    return series;
  }

  List<BookingView> occurrencesOf(BookingSeries series, Instant now, boolean withNames) {
    return lookup.viewsOf(bookings.findBySeriesIdOrderByStartAt(series.getId()), now, withNames);
  }

  /** What the caller asked to repeat. */
  public record NewSeries(
      UUID roomId,
      Instant firstStartAt,
      Instant firstEndAt,
      Frequency frequency,
      int count,
      int attendees,
      String purpose) {}

  /** One date of a series, and if it cannot be booked, why. */
  public record Occurrence(
      Instant startAt, Instant endAt, boolean bookable, ErrorCode reason, String detail) {}

  /** A series with its bookings, and the dates skipped when it was created. */
  public record CreatedSeries(
      BookingSeries series, List<BookingView> bookings, List<Occurrence> skipped) {}
}
