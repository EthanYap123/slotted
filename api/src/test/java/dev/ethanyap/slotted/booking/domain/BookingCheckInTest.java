package dev.ethanyap.slotted.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Check-in (BR-16, BR-17) and ending early (BR-15) at their exact boundaries. */
class BookingCheckInTest {

  private static final BookingPolicy POLICY = BookingPolicy.defaults();
  private static final ZoneId SGT = ZoneId.of("Asia/Singapore");
  private static final Instant START = Instant.parse("2026-11-04T02:00:00Z"); // 10:00 SGT
  private static final Instant END = START.plus(Duration.ofHours(2)); // 12:00 SGT

  private static Booking booking() {
    return new Booking(UUID.randomUUID(), UUID.randomUUID(), START, END, 1, null);
  }

  private static Booking checkedIn() {
    var booking = booking();
    booking.checkIn(START, POLICY);
    return booking;
  }

  private static Instant sgt(String time) {
    return Instant.parse("2026-11-04T" + time + "+08:00");
  }

  @Test
  void checkInOpensExactlyTenMinutesBeforeTheStart() {
    var tooEarly = booking();
    assertThat(tooEarly.isCheckInOpen(sgt("09:49:59.999"), POLICY)).isFalse();
    assertCode(() -> tooEarly.checkIn(sgt("09:49:59.999"), POLICY), ErrorCode.CHECK_IN_NOT_OPEN);

    var onTime = booking();
    assertThat(onTime.isCheckInOpen(sgt("09:50:00"), POLICY)).isTrue();
    onTime.checkIn(sgt("09:50:00"), POLICY);
    assertThat(onTime.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
    assertThat(onTime.getCheckedInAt()).isEqualTo(sgt("09:50:00"));
  }

  @Test
  void checkInClosesExactlyFifteenMinutesAfterTheStart() {
    var justInTime = booking();
    justInTime.checkIn(sgt("10:14:59.999999"), POLICY);
    assertThat(justInTime.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);

    var late = booking();
    assertThat(late.isCheckInOpen(sgt("10:15:00"), POLICY)).isFalse();
    assertCode(() -> late.checkIn(sgt("10:15:00"), POLICY), ErrorCode.CHECK_IN_CLOSED);
    assertThat(late.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
  }

  @Test
  void checkingInAgainChangesNothing() {
    var booking = checkedIn();

    booking.checkIn(sgt("10:20:00"), POLICY);

    assertThat(booking.getCheckedInAt()).isEqualTo(START);
  }

  @Test
  void aCancelledBookingCannotBeCheckedIn() {
    var booking = booking();
    booking.cancelByOwner(sgt("08:00:00"), POLICY, null);

    assertThat(booking.isCheckInOpen(START, POLICY)).isFalse();
    assertCode(() -> booking.checkIn(START, POLICY), ErrorCode.NO_BOOKING_TO_CHECK_IN);
  }

  @ParameterizedTest(name = "ending at {0} releases from {1}")
  @CsvSource({
    "10:07:00, 10:30:00",
    "10:29:59.999999, 10:30:00",
    "10:30:00, 10:30:00",
    "10:30:00.000001, 11:00:00",
    "11:45:00, 12:00:00",
  })
  void endingEarlyReleasesFromTheNextHalfHour(String now, String newEnd) {
    var booking = checkedIn();

    booking.endEarly(sgt(now), POLICY, SGT);

    assertThat(booking.getEndAt()).isEqualTo(sgt(newEnd));
    assertThat(booking.getReleasedAt()).isEqualTo(sgt(now));
  }

  @Test
  void aBookingEndedBeforeItStartsKeepsItsFirstHalfHour() {
    var booking = booking();
    booking.checkIn(sgt("09:52:00"), POLICY);

    booking.endEarly(sgt("09:55:00"), POLICY, SGT);

    assertThat(booking.getEndAt()).isEqualTo(sgt("10:30:00"));
  }

  @Test
  void endingEarlyNeverMakesABookingLonger() {
    var shortOne =
        new Booking(UUID.randomUUID(), UUID.randomUUID(), START, sgt("10:30:00"), 1, null);
    shortOne.checkIn(START, POLICY);

    shortOne.endEarly(sgt("10:20:00"), POLICY, SGT);

    assertThat(shortOne.getEndAt()).isEqualTo(sgt("10:30:00"));
  }

  @Test
  void aBookingCanOnlyBeEndedEarlyOnce() {
    var booking = checkedIn();
    booking.endEarly(sgt("10:07:00"), POLICY, SGT);

    assertCode(() -> booking.endEarly(sgt("10:20:00"), POLICY, SGT), ErrorCode.NOT_CHECKED_IN);
    assertThat(booking.getReleasedAt()).isEqualTo(sgt("10:07:00"));
  }

  @Test
  void onlyACheckedInBookingThatHasNotEndedCanBeEndedEarly() {
    assertCode(() -> booking().endEarly(sgt("10:05:00"), POLICY, SGT), ErrorCode.NOT_CHECKED_IN);
    assertCode(() -> checkedIn().endEarly(END, POLICY, SGT), ErrorCode.NOT_CHECKED_IN);
  }

  private static void assertCode(Runnable action, ErrorCode code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
  }
}
