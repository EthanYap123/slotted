package dev.ethanyap.slotted.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ethanyap.slotted.analytics.domain.Utilisation.BookingFact;
import dev.ethanyap.slotted.analytics.domain.Utilisation.HeatCell;
import dev.ethanyap.slotted.analytics.domain.Utilisation.Interval;
import dev.ethanyap.slotted.analytics.domain.Utilisation.Outcome;
import dev.ethanyap.slotted.analytics.domain.Utilisation.RoomInput;
import dev.ethanyap.slotted.facility.domain.OpeningPeriod;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PRD AD-4: the numbers in the utilisation report, without a database. */
class UtilisationTest {

  private static final ZoneId SGT = ZoneId.of("Asia/Singapore");

  /** A Monday. */
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

  private final UUID roomA = UUID.randomUUID();
  private final UUID roomB = UUID.randomUUID();

  @Test
  void openTimeFollowsOpeningHoursAndClosedDaysCountNothing() {
    var room = room(roomA, List.of(monday("08:00", "10:00")), List.of());

    var report = Utilisation.calculate(MONDAY, MONDAY.plusDays(6), List.of(room), List.of());

    var usage = report.rooms().getFirst();
    assertThat(usage.openHours()).isEqualTo(2.0);
    assertThat(usage.bookedHours()).isZero();
    assertThat(usage.utilisation()).isZero();
    assertThat(report.heatmap()).containsExactly(new HeatCell(1, 8, 0.0), new HeatCell(1, 9, 0.0));
  }

  @Test
  void onlyCheckedInAndUpcomingBookingsCountAsUse() {
    var room = room(roomA, List.of(monday("08:00", "12:00")), List.of());
    var bookings =
        List.of(
            booking(roomA, "08:00", "09:00", Outcome.CHECKED_IN, false),
            booking(roomA, "09:00", "10:00", Outcome.UPCOMING, false),
            booking(roomA, "10:00", "11:00", Outcome.NO_SHOW, false),
            booking(roomA, "11:00", "12:00", Outcome.CANCELLED, true),
            booking(roomA, "11:00", "12:00", Outcome.CANCELLED, false));

    var usage = Utilisation.calculate(MONDAY, MONDAY, List.of(room), bookings).rooms().getFirst();

    assertThat(usage.openHours()).isEqualTo(4.0);
    assertThat(usage.bookedHours()).isEqualTo(2.0);
    assertThat(usage.utilisation()).isEqualTo(0.5);
    assertThat(usage.bookings()).isEqualTo(3);
    assertThat(usage.checkInRate()).isEqualTo(0.5);
    assertThat(usage.noShowRate()).isEqualTo(0.5);
    assertThat(usage.cancellations()).isEqualTo(2);
    assertThat(usage.lateCancellations()).isEqualTo(1);
  }

  @Test
  void attendanceRatesAreZeroUntilABookingHasStarted() {
    var room = room(roomA, List.of(monday("08:00", "12:00")), List.of());
    var bookings = List.of(booking(roomA, "08:00", "09:00", Outcome.UPCOMING, false));

    var usage = Utilisation.calculate(MONDAY, MONDAY, List.of(room), bookings).rooms().getFirst();

    assertThat(usage.bookings()).isEqualTo(1);
    assertThat(usage.checkInRate()).isZero();
    assertThat(usage.noShowRate()).isZero();
  }

  @Test
  void blackoutsAreNotOpenTimeEvenWhenTheyOverlapEachOther() {
    var blackouts =
        List.of(
            new Interval(at(MONDAY, "08:00"), at(MONDAY, "09:00")),
            new Interval(at(MONDAY, "08:30"), at(MONDAY, "09:15")));
    var room = room(roomA, List.of(monday("08:00", "12:00")), blackouts);
    var bookings = List.of(booking(roomA, "10:00", "11:00", Outcome.CHECKED_IN, false));

    var usage = Utilisation.calculate(MONDAY, MONDAY, List.of(room), bookings).rooms().getFirst();

    assertThat(usage.openHours()).isEqualTo(2.75);
    assertThat(usage.bookedHours()).isEqualTo(1.0);
    assertThat(usage.utilisation()).isEqualTo(0.364);
  }

  @Test
  void bookingsOutsideTheRangeAreLeftOut() {
    var room = room(roomA, List.of(monday("08:00", "12:00")), List.of());
    var nextWeek = MONDAY.plusWeeks(1);
    var bookings =
        List.of(
            new BookingFact(
                roomA, at(nextWeek, "08:00"), at(nextWeek, "09:00"), Outcome.CHECKED_IN, false));

    var usage = Utilisation.calculate(MONDAY, MONDAY, List.of(room), bookings).rooms().getFirst();

    assertThat(usage.bookedHours()).isZero();
    assertThat(usage.bookings()).isZero();
  }

  @Test
  void theHeatMapCombinesRoomsByWeekdayAndHour() {
    var rooms =
        List.of(
            room(roomA, List.of(monday("08:00", "10:00")), List.of()),
            room(roomB, List.of(monday("08:00", "09:00")), List.of()));
    var bookings =
        List.of(
            booking(roomA, "08:00", "09:00", Outcome.CHECKED_IN, false),
            booking(roomA, "09:00", "09:30", Outcome.CHECKED_IN, false));

    var report = Utilisation.calculate(MONDAY, MONDAY, rooms, bookings);

    assertThat(report.heatmap()).containsExactly(new HeatCell(1, 8, 0.5), new HeatCell(1, 9, 0.5));
    assertThat(report.rooms()).extracting(r -> r.utilisation()).containsExactly(0.75, 0.0);
  }

  private RoomInput room(UUID id, List<OpeningPeriod> hours, List<Interval> blackouts) {
    return new RoomInput(id, "Room", UUID.randomUUID(), "Library", SGT, hours, blackouts);
  }

  private static OpeningPeriod monday(String opens, String closes) {
    return new OpeningPeriod(DayOfWeek.MONDAY, LocalTime.parse(opens), LocalTime.parse(closes));
  }

  private static BookingFact booking(
      UUID room, String start, String end, Outcome outcome, boolean late) {
    return new BookingFact(room, at(MONDAY, start), at(MONDAY, end), outcome, late);
  }

  private static Instant at(LocalDate date, String time) {
    return date.atTime(LocalTime.parse(time)).atZone(SGT).toInstant();
  }
}
