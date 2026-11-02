package dev.ethanyap.slotted.booking.domain;

import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.facility.domain.OpeningPeriod;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * The booking rules in PRD 5.1, as one pure function of the request and the facts it depends on.
 * Room overlap (BR-6) is guaranteed by the database; the service also checks it first so people get
 * a clear message in the common case.
 */
public final class BookingRules {

  private static final int STEP_MINUTES = 30;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

  private BookingRules() {}

  /** A requested booking. */
  public record Request(Instant startAt, Instant endAt, int attendees) {}

  /** The room and its facility's hours on the requested day. */
  public record RoomFacts(
      boolean active,
      int capacity,
      int maxBookingMinutes,
      ZoneId zone,
      Optional<OpeningPeriod> openingHoursThatDay) {}

  /**
   * What is already true for the person booking. Quotas only apply to students.
   *
   * @param upcomingBookings bookings of theirs that have not started yet
   * @param minutesBookedThatDay minutes of active bookings on the same local day
   * @param overlapsOwnBooking whether they already hold an active booking at the same time
   * @param suspendedUntil end of a no-show suspension, if one is in force
   */
  public record BookerFacts(
      boolean student,
      long upcomingBookings,
      long minutesBookedThatDay,
      boolean overlapsOwnBooking,
      Optional<Instant> suspendedUntil) {}

  /** Throws an {@link ApiException} naming the first rule the request breaks. */
  public static void check(
      Request request, RoomFacts room, BookerFacts booker, Instant now, BookingPolicy policy) {
    var start = request.startAt().atZone(room.zone());
    var end = request.endAt().atZone(room.zone());
    var minutes = Duration.between(request.startAt(), request.endAt()).toMinutes();

    if (!onStep(start) || !onStep(end)) {
      throw fail(ErrorCode.NOT_ALIGNED, "Bookings start and end on the hour or half hour.");
    }
    if (minutes < STEP_MINUTES) {
      throw fail(ErrorCode.BOOKING_TOO_SHORT, "Bookings are at least 30 minutes long.");
    }
    if (minutes > room.maxBookingMinutes()) {
      throw fail(
          ErrorCode.BOOKING_TOO_LONG,
          "Bookings for this room can be at most " + describe(room.maxBookingMinutes()) + " long.");
    }
    if (!request.startAt().isAfter(now)) {
      throw fail(ErrorCode.OUTSIDE_BOOKING_WINDOW, "Choose a time in the future.");
    }
    if (request.startAt().isAfter(now.plus(policy.window()))) {
      throw fail(
          ErrorCode.OUTSIDE_BOOKING_WINDOW,
          "You can book up to " + policy.window().toDays() + " days ahead.");
    }
    if (!room.active()) {
      throw fail(ErrorCode.ROOM_INACTIVE, "This room is not available for booking.");
    }
    checkOpeningHours(start, end, room.openingHoursThatDay());
    if (request.attendees() > room.capacity()) {
      throw fail(
          ErrorCode.OVER_CAPACITY, "This room holds at most " + room.capacity() + " people.");
    }
    if (booker.student()) {
      checkStudentLimits(minutes, booker, now, policy);
    }
  }

  private static void checkOpeningHours(
      ZonedDateTime start, ZonedDateTime end, Optional<OpeningPeriod> hours) {
    if (hours.isEmpty()) {
      throw fail(ErrorCode.OUTSIDE_OPENING_HOURS, "The facility is closed on this day.");
    }
    var open = hours.get();
    var sameDay = start.toLocalDate().equals(end.toLocalDate());
    if (!sameDay
        || start.toLocalTime().isBefore(open.opensAt())
        || end.toLocalTime().isAfter(open.closesAt())) {
      throw fail(
          ErrorCode.OUTSIDE_OPENING_HOURS,
          "The facility is open from "
              + TIME.format(open.opensAt())
              + " to "
              + TIME.format(open.closesAt())
              + " on this day.");
    }
  }

  private static void checkStudentLimits(
      long minutes, BookerFacts booker, Instant now, BookingPolicy policy) {
    if (booker.suspendedUntil().filter(now::isBefore).isPresent()) {
      throw fail(
          ErrorCode.SUSPENDED,
          "You cannot book until your no-show suspension ends. Ask an admin if this is a mistake.");
    }
    if (booker.upcomingBookings() >= policy.maxUpcoming()) {
      throw fail(
          ErrorCode.QUOTA_UPCOMING_EXCEEDED,
          "You can hold at most " + policy.maxUpcoming() + " upcoming bookings.");
    }
    if (booker.minutesBookedThatDay() + minutes > policy.maxDailyMinutes()) {
      throw fail(
          ErrorCode.QUOTA_DAILY_HOURS_EXCEEDED,
          "You can book at most " + describe(policy.maxDailyMinutes()) + " per day.");
    }
    if (booker.overlapsOwnBooking()) {
      throw fail(ErrorCode.STUDENT_OVERLAP, "You already have a booking at this time.");
    }
  }

  private static boolean onStep(ZonedDateTime time) {
    return time.getMinute() % STEP_MINUTES == 0 && time.getSecond() == 0 && time.getNano() == 0;
  }

  /** 120 → "2 hours", 90 → "1 hour 30 minutes". */
  static String describe(long minutes) {
    var hours = minutes / 60;
    var rest = minutes % 60;
    var text = new StringBuilder();
    if (hours > 0) {
      text.append(hours).append(hours == 1 ? " hour" : " hours");
    }
    if (rest > 0) {
      text.append(text.isEmpty() ? "" : " ").append(rest).append(" minutes");
    }
    return text.toString();
  }

  private static ApiException fail(ErrorCode code, String detail) {
    return new ApiException(code, detail);
  }
}
