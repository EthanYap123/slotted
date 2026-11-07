package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.application.BookingService.NewBooking;
import dev.ethanyap.slotted.booking.application.SeriesService.CreatedSeries;
import dev.ethanyap.slotted.booking.application.SeriesService.NewSeries;
import dev.ethanyap.slotted.booking.application.WaitlistService.NewEntry;
import dev.ethanyap.slotted.booking.application.WaitlistService.WaitlistView;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Writes that may take time in a room: first records no-shows and promotes waiting students in a
 * transaction of their own (ADR-0007), then does the write.
 *
 * <p>A booking retries when Postgres aborts the transaction to break a deadlock (ADR-0006).
 * Concurrent inserts into the same room can each wait for the other's uncommitted row in the
 * exclusion constraint's index; Postgres then aborts one with SQLSTATE 40P01. A retry runs in a
 * fresh transaction, sees whichever booking committed, and gives the normal answer.
 */
@Component
public class BookingAttempts {

  static final int MAX_ATTEMPTS = 3;

  private static final Logger log = LoggerFactory.getLogger(BookingAttempts.class);

  private final BookingService bookings;
  private final SeriesService series;
  private final WaitlistService waitlist;
  private final WaitlistQueue queue;

  public BookingAttempts(
      BookingService bookings,
      SeriesService series,
      WaitlistService waitlist,
      WaitlistQueue queue) {
    this.bookings = bookings;
    this.series = series;
    this.waitlist = waitlist;
    this.queue = queue;
  }

  public BookingView create(UUID userId, NewBooking request) {
    for (var attempt = 1; ; attempt++) {
      try {
        queue.settleAndPromote(request.roomId());
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

  /** Creates a series after giving waiting students their turn (ADR-0007). */
  public CreatedSeries createSeries(UUID userId, NewSeries request) {
    queue.settleAndPromote(request.roomId());
    return series.create(userId, request);
  }

  /** Joins a waitlist after giving those already waiting their turn (ADR-0007). */
  public WaitlistView joinWaitlist(UUID userId, NewEntry request) {
    queue.settleAndPromote(request.roomId());
    return waitlist.join(userId, request);
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
