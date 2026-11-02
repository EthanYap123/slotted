package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.application.BookingService.NewBooking;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Makes a booking, retrying when Postgres aborts the transaction to break a deadlock (ADR-0006).
 * Concurrent inserts into the same room can each wait for the other's uncommitted row in the
 * exclusion constraint's index; Postgres then aborts one with SQLSTATE 40P01. A retry runs in a
 * fresh transaction, sees whichever booking committed, and gives the normal answer.
 */
@Component
public class BookingAttempts {

  static final int MAX_ATTEMPTS = 3;

  private static final Logger log = LoggerFactory.getLogger(BookingAttempts.class);

  private final BookingService bookings;

  public BookingAttempts(BookingService bookings) {
    this.bookings = bookings;
  }

  public BookingView create(UUID userId, NewBooking request) {
    for (var attempt = 1; ; attempt++) {
      try {
        return bookings.create(userId, request);
      } catch (PessimisticLockingFailureException e) {
        if (attempt == MAX_ATTEMPTS) {
          log.warn(
              "Booking still deadlocked after {} attempts; reporting the slot as taken", attempt);
          throw new ApiException(
              ErrorCode.SLOT_TAKEN, "Several people are booking this room right now. Try again.");
        }
        log.info("Booking transaction lost a deadlock (attempt {}); retrying", attempt);
        pause();
      }
    }
  }

  /** A short random pause so the retries do not collide again. */
  private static void pause() {
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(10, 60));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while retrying a booking", e);
    }
  }
}
