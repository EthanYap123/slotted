package dev.ethanyap.slotted.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PRD BR-28: an entry expires from the moment its slot starts, whatever is stored. */
class WaitlistEntryTest {

  private static final Instant START = Instant.parse("2026-11-10T02:00:00Z");

  private final WaitlistEntry entry =
      new WaitlistEntry(
          UUID.randomUUID(),
          UUID.randomUUID(),
          START,
          START.plus(Duration.ofHours(1)),
          START.minus(Duration.ofDays(1)));

  @Test
  void itIsWaitingUntilJustBeforeTheSlotStarts() {
    var justBefore = START.minusNanos(1000);
    assertThat(entry.effectiveStatus(justBefore)).isEqualTo(WaitlistStatus.WAITING);
    assertThat(entry.isWaitingAt(justBefore)).isTrue();
  }

  @Test
  void itExpiresAtTheMomentTheSlotStarts() {
    assertThat(entry.effectiveStatus(START)).isEqualTo(WaitlistStatus.EXPIRED);
    assertThat(entry.isWaitingAt(START)).isFalse();
  }

  @Test
  void aPromotedEntryStaysPromotedAfterTheSlotStarts() {
    var booking = UUID.randomUUID();
    entry.markPromoted(booking, START.minus(Duration.ofHours(1)));

    assertThat(entry.effectiveStatus(START.plus(Duration.ofDays(1))))
        .isEqualTo(WaitlistStatus.PROMOTED);
    assertThat(entry.getPromotedBookingId()).isEqualTo(booking);
  }

  @Test
  void aCancelledEntryStaysCancelledAfterTheSlotStarts() {
    entry.markCancelled(START.minus(Duration.ofHours(1)));

    assertThat(entry.effectiveStatus(START.plus(Duration.ofDays(1))))
        .isEqualTo(WaitlistStatus.CANCELLED);
  }

  @Test
  void aStoredExpiryCountsBeforeTheSlotStarts() {
    entry.markExpired(START.minus(Duration.ofHours(2)));

    assertThat(entry.effectiveStatus(START.minus(Duration.ofHours(1))))
        .isEqualTo(WaitlistStatus.EXPIRED);
  }
}
