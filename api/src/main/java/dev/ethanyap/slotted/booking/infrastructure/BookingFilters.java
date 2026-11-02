package dev.ethanyap.slotted.booking.infrastructure;

import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingStatus;
import dev.ethanyap.slotted.booking.domain.EffectiveStatus;
import java.time.Instant;
import java.util.Collection;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filters for the manager booking list. A null value adds no filter. Status filters use the
 * effective status at {@code now}, the same rules as {@code Booking.effectiveStatus} (ADR-0004).
 */
public final class BookingFilters {

  private BookingFilters() {}

  public static Specification<Booking> inRooms(Collection<?> roomIds) {
    return roomIds == null ? null : (b, q, cb) -> b.get("roomId").in(roomIds);
  }

  public static Specification<Booking> overlapping(Instant from, Instant to) {
    return Specification.allOf(
        from == null ? null : (b, q, cb) -> cb.greaterThan(b.get("endAt"), from),
        to == null ? null : (b, q, cb) -> cb.lessThan(b.get("startAt"), to));
  }

  /**
   * Bookings whose effective status at {@code now} is {@code status}, where {@code cutoff} is now
   * minus the no-show grace period.
   */
  public static Specification<Booking> effectiveStatus(
      EffectiveStatus status, Instant now, Instant cutoff) {
    if (status == null) {
      return null;
    }
    return (b, q, cb) -> {
      var stored = b.<BookingStatus>get("status");
      var startAt = b.<Instant>get("startAt");
      var endAt = b.<Instant>get("endAt");
      var confirmedNotYetNoShow =
          cb.and(cb.equal(stored, BookingStatus.CONFIRMED), cb.greaterThan(startAt, cutoff));
      return switch (status) {
        case CANCELLED -> cb.equal(stored, BookingStatus.CANCELLED);
        case NO_SHOW ->
            cb.or(
                cb.equal(stored, BookingStatus.NO_SHOW),
                cb.and(
                    cb.equal(stored, BookingStatus.CONFIRMED),
                    cb.lessThanOrEqualTo(startAt, cutoff)));
        case CHECKED_IN ->
            cb.and(cb.equal(stored, BookingStatus.CHECKED_IN), cb.greaterThan(endAt, now));
        case CONFIRMED -> cb.and(confirmedNotYetNoShow, cb.greaterThan(endAt, now));
        case COMPLETED ->
            cb.and(
                cb.or(cb.equal(stored, BookingStatus.CHECKED_IN), confirmedNotYetNoShow),
                cb.lessThanOrEqualTo(endAt, now));
      };
    };
  }
}
