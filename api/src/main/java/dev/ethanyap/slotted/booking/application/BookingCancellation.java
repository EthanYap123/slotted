package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.application.SeriesService.CreatedSeries;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancelling bookings (BR-12 to BR-14, BR-23). Freed time goes to the waitlist in the same
 * transaction, so the cancellation and the promotion commit together or not at all (BR-26).
 */
@Service
public class BookingCancellation {

  private final BookingRepository bookings;
  private final BookingLookup lookup;
  private final BookingQueries queries;
  private final WaitlistQueue queue;
  private final BookingNotifier notifier;
  private final BookingPolicy policy;
  private final Clock clock;

  BookingCancellation(
      BookingRepository bookings,
      BookingLookup lookup,
      BookingQueries queries,
      WaitlistQueue queue,
      BookingNotifier notifier,
      BookingPolicy policy,
      Clock clock) {
    this.bookings = bookings;
    this.lookup = lookup;
    this.queries = queries;
    this.queue = queue;
    this.notifier = notifier;
    this.policy = policy;
    this.clock = clock;
  }

  /**
   * The owner cancels before the start; managers and admins cancel any active booking. The room is
   * locked before the booking is read, so its status cannot change underneath this.
   */
  @Transactional
  public BookingView cancel(UUID actorId, UUID bookingId, String reason) {
    var roomId =
        bookings.findRoomIdById(bookingId).orElseThrow(BookingCancellation::bookingNotFound);
    var room = lookup.lockRoom(roomId);
    var now = clock.instant();
    queue.settle(roomId, now);
    var booking = bookings.findById(bookingId).orElseThrow(BookingCancellation::bookingNotFound);
    if (booking.getUserId().equals(actorId)) {
      booking.cancelByOwner(now, policy, reason);
      notifier.cancelled(List.of(booking), null);
    } else if (queries.canManage(room.getFacilityId(), actorId)) {
      booking.cancelByStaff(actorId, now, policy, reason);
      notifier.cancelled(List.of(booking), reason);
    } else {
      throw bookingNotFound();
    }
    queue.promoteIn(room, now);
    return lookup.view(booking, room, lookup.facilityOf(room), now, null);
  }

  /**
   * Cancels every occurrence from a date onwards (BR-23). The owner can only cancel what has not
   * started; managers and admins follow their usual rule and must give a reason.
   */
  @Transactional
  public CreatedSeries cancelSeriesFrom(
      UUID actorId, UUID seriesId, LocalDate fromDate, String reason) {
    var cancelling = queries.visibleSeries(actorId, seriesId);
    var owner = cancelling.getUserId().equals(actorId);
    var room = lookup.lockRoom(cancelling.getRoomId());
    var zone = ZoneId.of(lookup.facilityOf(room).getTimeZone());
    var from = fromDate.atStartOfDay(zone).toInstant();
    var now = clock.instant();
    queue.settle(room.getId(), now);

    var toCancel =
        bookings.findBySeriesIdOrderByStartAt(cancelling.getId()).stream()
            .filter(b -> !b.getStartAt().isBefore(from))
            .filter(b -> b.effectiveStatus(now, policy).blocksRoom())
            .filter(b -> !owner || b.getStartAt().isAfter(now))
            .toList();
    if (toCancel.isEmpty()) {
      throw new ApiException(
          ErrorCode.NOT_CANCELLABLE, "No bookings in this series can be cancelled from that date.");
    }
    for (var booking : toCancel) {
      if (owner) {
        booking.cancelByOwner(now, policy, reason);
      } else {
        booking.cancelByStaff(actorId, now, policy, reason);
      }
    }
    notifier.cancelled(toCancel, owner ? null : reason);
    var anyLeft =
        bookings.findBySeriesIdOrderByStartAt(cancelling.getId()).stream()
            .anyMatch(b -> b.getStartAt().isAfter(now) && b.getStatus().isActive());
    if (!anyLeft) {
      cancelling.markCancelled(now);
    }
    queue.promoteIn(room, now);
    return new CreatedSeries(cancelling, queries.occurrencesOf(cancelling, now, !owner), List.of());
  }

  private static ApiException bookingNotFound() {
    return new ApiException(ErrorCode.NOT_FOUND, "No booking with this ID.");
  }
}
