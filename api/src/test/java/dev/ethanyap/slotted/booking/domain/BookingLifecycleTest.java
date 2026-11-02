package dev.ethanyap.slotted.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Effective status (ADR-0004), cancellation (BR-12 to BR-14) and suspensions (BR-18). */
class BookingLifecycleTest {

  private static final BookingPolicy POLICY = BookingPolicy.defaults();
  private static final Instant START = Instant.parse("2026-11-04T02:00:00Z"); // 10:00 SGT
  private static final Instant END = START.plus(Duration.ofHours(1));
  private static final UUID OWNER = UUID.randomUUID();

  @Test
  void aConfirmedBookingBecomesANoShowFifteenMinutesAfterItsStart() {
    var booking = booking();

    assertThat(booking.effectiveStatus(START.minusSeconds(1), POLICY))
        .isEqualTo(EffectiveStatus.CONFIRMED);
    assertThat(booking.effectiveStatus(START.plus(Duration.ofMinutes(14)), POLICY))
        .isEqualTo(EffectiveStatus.CONFIRMED);
    assertThat(booking.effectiveStatus(START.plus(Duration.ofMinutes(15)), POLICY))
        .isEqualTo(EffectiveStatus.NO_SHOW);
    assertThat(booking.effectiveStatus(END.plusSeconds(1), POLICY))
        .isEqualTo(EffectiveStatus.NO_SHOW);
  }

  @Test
  void checkInOpensTenMinutesBeforeTheStart() {
    assertThat(booking().checkInOpensAt(POLICY)).isEqualTo(START.minus(Duration.ofMinutes(10)));
    assertThat(booking().noShowAt(POLICY)).isEqualTo(START.plus(Duration.ofMinutes(15)));
  }

  @Test
  void anOwnerCancelsBeforeTheStart() {
    var booking = booking();
    var now = START.minus(Duration.ofHours(3));

    booking.cancelByOwner(now, POLICY, "  Plans changed ");

    assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(booking.getCancelledAt()).isEqualTo(now);
    assertThat(booking.getCancelReason()).isEqualTo("Plans changed");
    assertThat(booking.isLateCancellation()).isFalse();
    assertThat(booking.effectiveStatus(now, POLICY)).isEqualTo(EffectiveStatus.CANCELLED);
  }

  @Test
  void cancellingWithinTwoHoursIsLate() {
    var exactlyTwoHours = booking();
    var justUnder = booking();

    exactlyTwoHours.cancelByOwner(START.minus(Duration.ofHours(2)), POLICY, null);
    justUnder.cancelByOwner(START.minus(Duration.ofHours(2)).plusSeconds(1), POLICY, null);

    assertThat(exactlyTwoHours.isLateCancellation()).isFalse();
    assertThat(justUnder.isLateCancellation()).isTrue();
  }

  @Test
  void anOwnerCannotCancelOnceTheBookingHasStarted() {
    assertThatThrownBy(() -> booking().cancelByOwner(START, POLICY, null))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ALREADY_STARTED));
  }

  @Test
  void cancelledNoShowAndFinishedBookingsCannotBeCancelled() {
    var cancelled = booking();
    cancelled.cancelByOwner(START.minus(Duration.ofDays(1)), POLICY, null);

    for (var attempt :
        List.<Runnable>of(
            () -> cancelled.cancelByOwner(START.minus(Duration.ofHours(1)), POLICY, null),
            () -> booking().cancelByOwner(START.plus(Duration.ofMinutes(20)), POLICY, null),
            () -> booking().cancelByStaff(UUID.randomUUID(), END, POLICY, "Late"))) {
      assertThatThrownBy(attempt::run)
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_CANCELLABLE));
    }
  }

  @Test
  void staffCancelAnyActiveBookingButMustGiveAReason() {
    var staff = UUID.randomUUID();
    var booking = booking();

    assertThatThrownBy(() -> booking.cancelByStaff(staff, START, POLICY, " "))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.REASON_REQUIRED));

    booking.cancelByStaff(staff, START.plus(Duration.ofMinutes(5)), POLICY, "Water leak");

    assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(booking.getCancelReason()).isEqualTo("Water leak");
  }

  @Test
  void threeNoShowsInThirtyDaysSuspendForSevenDaysFromTheLatest() {
    var now = Instant.parse("2026-11-20T00:00:00Z");
    var noShows = List.of(daysBefore(now, 25), daysBefore(now, 10), daysBefore(now, 2));

    assertThat(Suspension.until(noShows, null, now, POLICY))
        .contains(daysBefore(now, 2).plus(Duration.ofDays(7)));
  }

  @Test
  void twoNoShowsOrOldOnesDoNotSuspend() {
    var now = Instant.parse("2026-11-20T00:00:00Z");

    assertThat(Suspension.until(List.of(daysBefore(now, 3), daysBefore(now, 2)), null, now, POLICY))
        .isEmpty();
    assertThat(
            Suspension.until(
                List.of(daysBefore(now, 31), daysBefore(now, 3), daysBefore(now, 2)),
                null,
                now,
                POLICY))
        .isEmpty();
  }

  @Test
  void aSuspensionEndsAfterSevenDays() {
    var now = Instant.parse("2026-11-20T00:00:00Z");
    var noShows = List.of(daysBefore(now, 9), daysBefore(now, 8), daysBefore(now, 7));

    assertThat(Suspension.until(noShows, null, now, POLICY)).isEmpty();
  }

  @Test
  void liftingASuspensionForgivesEarlierNoShows() {
    var now = Instant.parse("2026-11-20T00:00:00Z");
    var noShows = List.of(daysBefore(now, 5), daysBefore(now, 4), daysBefore(now, 3));

    assertThat(Suspension.until(noShows, daysBefore(now, 1), now, POLICY)).isEmpty();
  }

  private static Instant daysBefore(Instant now, int days) {
    return now.minus(Duration.ofDays(days));
  }

  private static Booking booking() {
    return new Booking(UUID.randomUUID(), OWNER, START, END, 2, null);
  }
}
