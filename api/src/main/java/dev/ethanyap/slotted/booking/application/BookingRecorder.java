package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.audit.application.AuditLog;
import dev.ethanyap.slotted.audit.domain.AuditAction;
import dev.ethanyap.slotted.audit.domain.Change;
import dev.ethanyap.slotted.booking.application.SeriesService.Occurrence;
import dev.ethanyap.slotted.booking.domain.Blackout;
import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingStatus;
import dev.ethanyap.slotted.booking.domain.WaitlistEntry;
import dev.ethanyap.slotted.booking.domain.WaitlistStatus;
import dev.ethanyap.slotted.facility.domain.Facility;
import dev.ethanyap.slotted.user.domain.User;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Writes down what a booking change leaves behind, inside the change's transaction: an audit record
 * for every booking, waitlist entry, blackout or opening-hours change (BR-32), and the emails it
 * sends. Either both are committed with the change or neither is.
 *
 * <p>An actor of {@code null} means the system did it, for example a waitlist promotion.
 */
@Component
class BookingRecorder {

  private final AuditLog audit;
  private final BookingNotifier notifier;
  private final BookingLookup lookup;

  BookingRecorder(AuditLog audit, BookingNotifier notifier, BookingLookup lookup) {
    this.audit = audit;
    this.notifier = notifier;
    this.lookup = lookup;
  }

  /** A new booking, confirmed by email (ST-3). */
  void created(UUID actorId, Booking booking) {
    audit.record(createdChange(AuditAction.BOOKING_CREATED, actorId, booking));
    notifier.confirmed(booking);
  }

  /** The dates of a new series, confirmed in one email (ST-6). */
  void seriesCreated(UUID actorId, List<Booking> booked, List<Occurrence> skipped) {
    audit.recordAll(
        booked.stream().map(b -> createdChange(AuditAction.BOOKING_CREATED, actorId, b)).toList());
    notifier.seriesConfirmed(booked, skipped);
  }

  /** A waiting student got the slot (BR-26); the system did it. */
  void promoted(WaitlistEntry entry, Booking booking, Instant now) {
    change(null, AuditAction.WAITLIST_PROMOTED, entry, e -> e.markPromoted(booking.getId(), now));
    audit.record(createdChange(AuditAction.BOOKING_PROMOTED, null, booking));
    notifier.promoted(booking);
  }

  /**
   * Cancels bookings with {@code cancel}, records each and emails the people affected.
   *
   * @param reasonForEmail shown in the email; null when people cancelled their own booking
   */
  void cancel(
      UUID actorId, Collection<Booking> bookings, Consumer<Booking> cancel, String reasonForEmail) {
    bookings.forEach(b -> change(actorId, AuditAction.BOOKING_CANCELLED, b, cancel));
    notifier.cancelled(bookings, reasonForEmail);
  }

  /**
   * Applies {@code change} to a booking and records the values before and after. A change that
   * alters nothing, such as checking in twice, is not recorded.
   */
  void change(UUID actorId, AuditAction action, Booking booking, Consumer<Booking> change) {
    var before = booking.auditState();
    change.accept(booking);
    record(action, booking.getId(), booking.getRoomId(), actorId, before, booking.auditState());
  }

  /** The same for a waitlist entry. */
  void change(
      UUID actorId, AuditAction action, WaitlistEntry entry, Consumer<WaitlistEntry> change) {
    var before = entry.auditState();
    change.accept(entry);
    record(action, entry.getId(), entry.getRoomId(), actorId, before, entry.auditState());
  }

  /** A student joined a waitlist (ST-7). */
  void joined(WaitlistEntry entry) {
    record(
        AuditAction.WAITLIST_JOINED,
        entry.getId(),
        entry.getRoomId(),
        entry.getUserId(),
        null,
        entry.auditState());
  }

  /**
   * No-shows the settle step wrote down in one statement (ADR-0004). Only the status is known
   * without loading each booking, so only the status is recorded.
   */
  void noShows(UUID roomId, Collection<UUID> bookingIds) {
    var facilityId = lookup.room(roomId).getFacilityId();
    audit.recordAll(
        bookingIds.stream()
            .map(
                id ->
                    new Change(
                        AuditAction.BOOKING_NO_SHOW,
                        id,
                        facilityId,
                        null,
                        Map.of("status", BookingStatus.CONFIRMED),
                        Map.of("status", BookingStatus.NO_SHOW)))
            .toList());
  }

  /** Entries the sweep wrote down as expired, by the room they are in (BR-28). */
  void expired(Map<UUID, List<UUID>> entryIdsByRoom) {
    entryIdsByRoom.forEach(
        (roomId, entryIds) -> {
          var facilityId = lookup.room(roomId).getFacilityId();
          audit.recordAll(
              entryIds.stream()
                  .map(
                      id ->
                          new Change(
                              AuditAction.WAITLIST_EXPIRED,
                              id,
                              facilityId,
                              null,
                              Map.of("status", WaitlistStatus.WAITING),
                              Map.of("status", WaitlistStatus.EXPIRED)))
                  .toList());
        });
  }

  void blackoutCreated(UUID actorId, Blackout blackout) {
    audit.record(
        new Change(
            AuditAction.BLACKOUT_CREATED,
            blackout.getId(),
            blackout.getFacilityId(),
            actorId,
            null,
            blackout.auditState()));
  }

  void blackoutDeleted(UUID actorId, Blackout blackout) {
    audit.record(
        new Change(
            AuditAction.BLACKOUT_DELETED,
            blackout.getId(),
            blackout.getFacilityId(),
            actorId,
            blackout.auditState(),
            null));
  }

  /** Replaces a facility's opening hours with {@code change} and records both weeks. */
  void openingHoursChanged(UUID actorId, Facility facility, Consumer<Facility> change) {
    var before = facility.openingHoursAuditState();
    change.accept(facility);
    audit.record(
        new Change(
            AuditAction.OPENING_HOURS_CHANGED,
            facility.getId(),
            facility.getId(),
            actorId,
            before,
            facility.openingHoursAuditState()));
  }

  /** A student was suspended for no-shows (BR-18); derived, so there is nothing to record. */
  void suspended(User user, Instant until, ZoneId zone) {
    notifier.suspended(user, until, zone);
  }

  private Change createdChange(AuditAction action, UUID actorId, Booking booking) {
    return new Change(
        action,
        booking.getId(),
        lookup.room(booking.getRoomId()).getFacilityId(),
        actorId,
        null,
        booking.auditState());
  }

  private void record(
      AuditAction action,
      UUID entityId,
      UUID roomId,
      UUID actorId,
      Map<String, ?> before,
      Map<String, ?> after) {
    var change =
        new Change(action, entityId, lookup.room(roomId).getFacilityId(), actorId, before, after);
    if (change.changesSomething()) {
      audit.record(change);
    }
  }
}
