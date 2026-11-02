package dev.ethanyap.slotted.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ethanyap.slotted.booking.domain.BookingRules.BookerFacts;
import dev.ethanyap.slotted.booking.domain.BookingRules.Request;
import dev.ethanyap.slotted.booking.domain.BookingRules.RoomFacts;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.facility.domain.OpeningPeriod;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** PRD 5.1 (BR-1 to BR-11) at their boundaries, with a fixed "now" in Singapore time. */
class BookingRulesTest {

  private static final ZoneId SGT = ZoneId.of("Asia/Singapore");
  private static final BookingPolicy POLICY = BookingPolicy.defaults();

  /** Tuesday 3 November 2026, 09:12 SGT. */
  private static final Instant NOW = sgt("2026-11-03T09:12");

  private static final OpeningPeriod WEDNESDAY_8_TO_22 =
      new OpeningPeriod(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(22, 0));
  private static final RoomFacts ROOM =
      new RoomFacts(true, 6, 120, SGT, Optional.of(WEDNESDAY_8_TO_22));
  private static final BookerFacts STUDENT = new BookerFacts(true, 0, 0, false, Optional.empty());
  private static final BookerFacts MANAGER =
      new BookerFacts(false, 99, 9999, true, Optional.empty());

  @Test
  void aValidBookingPasses() {
    assertThatCode(() -> check(request("2026-11-04T10:00", "2026-11-04T12:00", 6), ROOM, STUDENT))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @CsvSource({"2026-11-04T10:15,2026-11-04T11:00", "2026-11-04T10:00,2026-11-04T10:45"})
  void timesMustBeOnTheHourOrHalfHour(String start, String end) {
    assertFails(request(start, end, 2), ROOM, STUDENT, ErrorCode.NOT_ALIGNED);
  }

  @Test
  void alignmentIsCheckedInTheFacilitysZone() {
    // 10:00 in a zone with a 45-minute offset is not on a half hour in Singapore.
    var nepal = LocalDateTime.parse("2026-11-04T10:00").atZone(ZoneId.of("Asia/Kathmandu"));
    var start = nepal.toInstant();

    assertFails(
        new Request(start, start.plus(Duration.ofHours(1)), 2),
        ROOM,
        STUDENT,
        ErrorCode.NOT_ALIGNED);
  }

  @Test
  void bookingsAreAtLeastThirtyMinutes() {
    assertFails(
        request("2026-11-04T10:00", "2026-11-04T10:00", 2),
        ROOM,
        STUDENT,
        ErrorCode.BOOKING_TOO_SHORT);
    assertFails(
        request("2026-11-04T10:30", "2026-11-04T10:00", 2),
        ROOM,
        STUDENT,
        ErrorCode.BOOKING_TOO_SHORT);
  }

  @Test
  void bookingsAreAtMostTheRoomsMaximum() {
    assertThatCode(() -> check(request("2026-11-04T10:00", "2026-11-04T12:00", 2), ROOM, STUDENT))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> check(request("2026-11-04T10:00", "2026-11-04T12:30", 2), ROOM, STUDENT))
        .hasMessage("Bookings for this room can be at most 2 hours long.");
  }

  @Test
  void bookingsMustStartInTheFuture() {
    assertFails(
        request("2026-11-03T09:00", "2026-11-03T10:00", 2),
        ROOM,
        STUDENT,
        ErrorCode.OUTSIDE_BOOKING_WINDOW);
  }

  @Test
  void bookingsCanStartUpToFourteenDaysAhead() {
    var latest = NOW.plus(Duration.ofDays(14));
    var tuesdayAllDay =
        new OpeningPeriod(DayOfWeek.TUESDAY, LocalTime.of(0, 0), LocalTime.of(23, 30));
    var room = new RoomFacts(true, 6, 120, SGT, Optional.of(tuesdayAllDay));
    // 17 November 2026 09:00 SGT is before "now + 14 days" (09:12); 09:30 is after it.
    assertThatCode(() -> check(request("2026-11-17T09:00", "2026-11-17T10:00", 2), room, STUDENT))
        .doesNotThrowAnyException();
    assertThat(sgt("2026-11-17T09:30")).isAfter(latest);
    assertFails(
        request("2026-11-17T09:30", "2026-11-17T10:30", 2),
        room,
        STUDENT,
        ErrorCode.OUTSIDE_BOOKING_WINDOW);
  }

  @Test
  void inactiveRoomsCannotBeBooked() {
    var inactive = new RoomFacts(false, 6, 120, SGT, Optional.of(WEDNESDAY_8_TO_22));

    assertFails(
        request("2026-11-04T10:00", "2026-11-04T11:00", 2),
        inactive,
        STUDENT,
        ErrorCode.ROOM_INACTIVE);
  }

  @ParameterizedTest
  @CsvSource({
    "2026-11-04T07:30,2026-11-04T08:30",
    "2026-11-04T21:00,2026-11-04T22:30",
    "2026-11-04T23:30,2026-11-05T00:30"
  })
  void bookingsStayInsideOpeningHoursOnOneDay(String start, String end) {
    var allDays = new RoomFacts(true, 6, 120, SGT, Optional.of(WEDNESDAY_8_TO_22));

    assertFails(request(start, end, 2), allDays, STUDENT, ErrorCode.OUTSIDE_OPENING_HOURS);
  }

  @Test
  void openingAndClosingTimesAreBookable() {
    assertThatCode(() -> check(request("2026-11-04T08:00", "2026-11-04T09:00", 2), ROOM, STUDENT))
        .doesNotThrowAnyException();
    assertThatCode(() -> check(request("2026-11-04T21:00", "2026-11-04T22:00", 2), ROOM, STUDENT))
        .doesNotThrowAnyException();
  }

  @Test
  void aClosedDayCannotBeBooked() {
    var closed = new RoomFacts(true, 6, 120, SGT, Optional.empty());

    assertThatThrownBy(
            () -> check(request("2026-11-04T10:00", "2026-11-04T11:00", 2), closed, STUDENT))
        .hasMessage("The facility is closed on this day.");
  }

  @Test
  void attendeesMustFitTheRoom() {
    assertFails(
        request("2026-11-04T10:00", "2026-11-04T11:00", 7), ROOM, STUDENT, ErrorCode.OVER_CAPACITY);
  }

  @Test
  void aStudentHoldsAtMostThreeUpcomingBookings() {
    var two = new BookerFacts(true, 2, 0, false, Optional.empty());
    var three = new BookerFacts(true, 3, 0, false, Optional.empty());

    assertThatCode(() -> check(request("2026-11-04T10:00", "2026-11-04T11:00", 2), ROOM, two))
        .doesNotThrowAnyException();
    assertFails(
        request("2026-11-04T10:00", "2026-11-04T11:00", 2),
        ROOM,
        three,
        ErrorCode.QUOTA_UPCOMING_EXCEEDED);
  }

  @Test
  void aStudentBooksAtMostFourHoursADay() {
    var twoHoursBooked = new BookerFacts(true, 1, 120, false, Optional.empty());
    var threeAndAHalf = new BookerFacts(true, 1, 210, false, Optional.empty());

    assertThatCode(
            () -> check(request("2026-11-04T10:00", "2026-11-04T12:00", 2), ROOM, twoHoursBooked))
        .doesNotThrowAnyException();
    assertFails(
        request("2026-11-04T10:00", "2026-11-04T11:00", 2),
        ROOM,
        threeAndAHalf,
        ErrorCode.QUOTA_DAILY_HOURS_EXCEEDED);
  }

  @Test
  void aStudentCannotBeInTwoPlacesAtOnce() {
    var overlapping = new BookerFacts(true, 1, 60, true, Optional.empty());

    assertFails(
        request("2026-11-04T10:00", "2026-11-04T11:00", 2),
        ROOM,
        overlapping,
        ErrorCode.STUDENT_OVERLAP);
  }

  @Test
  void aSuspendedStudentCannotBookUntilTheSuspensionEnds() {
    var suspended = new BookerFacts(true, 0, 0, false, Optional.of(NOW.plusSeconds(1)));
    var ended = new BookerFacts(true, 0, 0, false, Optional.of(NOW));

    assertFails(
        request("2026-11-04T10:00", "2026-11-04T11:00", 2), ROOM, suspended, ErrorCode.SUSPENDED);
    assertThatCode(() -> check(request("2026-11-04T10:00", "2026-11-04T11:00", 2), ROOM, ended))
        .doesNotThrowAnyException();
  }

  @Test
  void managersAndAdminsHaveNoQuotas() {
    assertThatCode(() -> check(request("2026-11-04T10:00", "2026-11-04T12:00", 2), ROOM, MANAGER))
        .doesNotThrowAnyException();
  }

  @Test
  void lengthsAreDescribedInWords() {
    assertThat(BookingRules.describe(60)).isEqualTo("1 hour");
    assertThat(BookingRules.describe(90)).isEqualTo("1 hour 30 minutes");
    assertThat(BookingRules.describe(30)).isEqualTo("30 minutes");
    assertThat(BookingRules.describe(240)).isEqualTo("4 hours");
  }

  private static void check(Request request, RoomFacts room, BookerFacts booker) {
    BookingRules.check(request, room, booker, NOW, POLICY);
  }

  private static void assertFails(
      Request request, RoomFacts room, BookerFacts booker, ErrorCode code) {
    assertThatThrownBy(() -> check(request, room, booker))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  private static Request request(String start, String end, int attendees) {
    return new Request(sgt(start), sgt(end), attendees);
  }

  private static Instant sgt(String localDateTime) {
    return LocalDateTime.parse(localDateTime).atZone(SGT).toInstant();
  }
}
