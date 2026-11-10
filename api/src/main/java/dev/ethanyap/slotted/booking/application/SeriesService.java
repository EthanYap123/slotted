package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingSeries;
import dev.ethanyap.slotted.booking.domain.BookingSeries.Frequency;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.booking.infrastructure.BookingSeriesRepository;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import java.time.Clock;
import java.time.Instant;
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
  private final SeriesChecks checks;
  private final BookingLookup lookup;
  private final BookingRecorder recorder;
  private final WaitlistQueue queue;
  private final Clock clock;

  SeriesService(
      BookingSeriesRepository seriesRepository,
      BookingRepository bookings,
      SeriesChecks checks,
      BookingLookup lookup,
      BookingRecorder recorder,
      WaitlistQueue queue,
      Clock clock) {
    this.seriesRepository = seriesRepository;
    this.bookings = bookings;
    this.checks = checks;
    this.lookup = lookup;
    this.recorder = recorder;
    this.queue = queue;
    this.clock = clock;
  }

  /** Says which occurrences could be booked right now. Saves nothing. */
  @Transactional(readOnly = true)
  public List<Occurrence> preview(UUID userId, NewSeries request) {
    var user = lookup.activeUser(userId);
    var room = lookup.room(request.roomId());
    var facility = lookup.facilityOf(room);
    var now = clock.instant();
    checks.checkSeriesAsAWhole(user, request, facility, now);
    return checks.checkOccurrences(user, room, facility, request, now);
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
    checks.checkSeriesAsAWhole(user, request, facility, now);
    queue.settle(room.getId(), now);

    var occurrences = checks.checkOccurrences(user, room, facility, request, now);
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
    var booked = bookings.findBySeriesIdOrderByStartAt(series.getId());
    recorder.seriesCreated(user.getId(), booked, skipped);
    return new CreatedSeries(series, lookup.viewsOf(booked, now, false), skipped);
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
