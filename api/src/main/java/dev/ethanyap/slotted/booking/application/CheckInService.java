package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.BookingStatus;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checking in with a room's QR code and ending a checked-in booking early (PRD ST-8, BR-15 to
 * BR-17). Both take the room's lock, the same one the no-show settle step runs under, so a check-in
 * and the release of the same booking can never both happen (ADR-0004).
 */
@Service
public class CheckInService {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

  private final BookingRepository bookings;
  private final BookingLookup lookup;
  private final WaitlistQueue queue;
  private final BookingPolicy policy;
  private final Clock clock;

  CheckInService(
      BookingRepository bookings,
      BookingLookup lookup,
      WaitlistQueue queue,
      BookingPolicy policy,
      Clock clock) {
    this.bookings = bookings;
    this.lookup = lookup;
    this.queue = queue;
    this.policy = policy;
    this.clock = clock;
  }

  /**
   * Checks the caller in to their booking in the room the token belongs to. Scanning again after
   * checking in is harmless and returns the same booking.
   */
  @Transactional
  public BookingView checkIn(UUID userId, String token) {
    var found =
        lookup
            .roomByCheckInToken(token)
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.QR_TOKEN_INVALID,
                        "This QR code is no longer valid. Ask staff for the current one."));
    var room = lookup.lockRoom(found.getId());
    var facility = lookup.facilityOf(room);
    var zone = ZoneId.of(facility.getTimeZone());
    var now = clock.instant();
    var endOfDay = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
    var candidates =
        bookings.findForCheckIn(userId, room.getId(), now, endOfDay).stream()
            .sorted(Comparator.comparing(Booking::getStartAt))
            .toList();

    var open =
        candidates.stream()
            .filter(b -> b.getStatus() == BookingStatus.CHECKED_IN || b.isCheckInOpen(now, policy))
            .findFirst();
    if (open.isPresent()) {
      var booking = open.get();
      booking.checkIn(now, policy);
      return lookup.view(booking, room, facility, now, null);
    }
    if (candidates.stream().anyMatch(b -> !b.getStartAt().isAfter(now))) {
      throw new ApiException(
          ErrorCode.CHECK_IN_CLOSED,
          "Check-in closed "
              + policy.noShowGrace().toMinutes()
              + " minutes after the start, so this booking was released.");
    }
    var later = candidates.stream().findFirst();
    if (later.isPresent()) {
      throw new ApiException(
          ErrorCode.CHECK_IN_NOT_OPEN,
          "Check-in opens at "
              + TIME.format(later.get().checkInOpensAt(policy).atZone(zone))
              + ", "
              + policy.checkInOpensBefore().toMinutes()
              + " minutes before your booking starts.");
    }
    throw new ApiException(
        ErrorCode.NO_BOOKING_TO_CHECK_IN, "You have no booking in " + room.getName() + " now.");
  }

  /**
   * The owner ends a checked-in booking. The rest of the time is released from the next 30-minute
   * boundary and offered to the waitlist in the same transaction (BR-15, BR-26).
   */
  @Transactional
  public BookingView endEarly(UUID userId, UUID bookingId) {
    var roomId = bookings.findRoomIdById(bookingId).orElseThrow(CheckInService::notFound);
    var room = lookup.lockRoom(roomId);
    var facility = lookup.facilityOf(room);
    var now = clock.instant();
    var booking =
        bookings
            .findById(bookingId)
            .filter(b -> b.getUserId().equals(userId))
            .orElseThrow(CheckInService::notFound);
    booking.endEarly(now, policy, ZoneId.of(facility.getTimeZone()));
    queue.promoteIn(room, now);
    return lookup.view(booking, room, facility, now, null);
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.NOT_FOUND, "No booking with this ID.");
  }
}
