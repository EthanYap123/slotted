package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.application.SeriesService.Occurrence;
import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.facility.domain.Facility;
import dev.ethanyap.slotted.notification.application.EmailOutbox;
import dev.ethanyap.slotted.notification.domain.EmailContent;
import dev.ethanyap.slotted.notification.infrastructure.EmailProperties;
import dev.ethanyap.slotted.room.domain.Room;
import dev.ethanyap.slotted.user.domain.User;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Queues the emails that booking changes send (PRD ST-3, ST-4, ST-7, BR-14, BR-18, BR-26, BR-31,
 * AD-1). Called inside the change's transaction, so an email exists exactly when the change was
 * committed (ADR-0004). Times are written in the facility's zone.
 */
@Component
class BookingNotifier {

  private final EmailOutbox outbox;
  private final BookingLookup lookup;
  private final BookingPolicy policy;
  private final String bookingsUrl;

  BookingNotifier(
      EmailOutbox outbox, BookingLookup lookup, BookingPolicy policy, EmailProperties email) {
    this.outbox = outbox;
    this.lookup = lookup;
    this.policy = policy;
    this.bookingsUrl = email.webUrl().replaceAll("/+$", "") + "/bookings";
  }

  /** A new booking (ST-3). */
  void confirmed(Booking booking) {
    var place = place(booking.getRoomId());
    var user = lookup.user(booking.getUserId());
    var body =
        greeting(user)
            .paragraph("Your booking is confirmed.")
            .paragraph(place.describe(booking))
            .paragraph(checkInReminder())
            .link("See or cancel your bookings", bookingsUrl);
    send(user, "BOOKING_CONFIRMED", "Booked: " + place.subject(booking), body, null);
  }

  /** A repeating booking, in one email rather than one per date (ST-6). */
  void seriesConfirmed(List<Booking> booked, List<Occurrence> skipped) {
    var first = booked.getFirst();
    var place = place(first.getRoomId());
    var user = lookup.user(first.getUserId());
    var body =
        greeting(user)
            .paragraph("Your repeating booking of " + place.room().getName() + " is confirmed for:")
            .list(booked.stream().map(place::when).toList());
    if (!skipped.isEmpty()) {
      body.paragraph("These dates could not be booked:")
          .list(
              skipped.stream()
                  .map(
                      o ->
                          BookingEmails.when(o.startAt(), o.endAt(), place.zone())
                              + (o.detail() == null ? "" : " (" + o.detail() + ")"))
                  .toList());
    }
    body.paragraph(checkInReminder()).link("See or cancel your bookings", bookingsUrl);
    send(
        user,
        "SERIES_CONFIRMED",
        "Repeating booking: " + place.room().getName() + ", " + booked.size() + " dates",
        body,
        null);
  }

  /** A waiting student got the slot (ST-7, BR-26). */
  void promoted(Booking booking) {
    var place = place(booking.getRoomId());
    var user = lookup.user(booking.getUserId());
    var body =
        greeting(user)
            .paragraph("A slot you were waiting for came free, and it is now booked for you.")
            .paragraph(place.describe(booking))
            .paragraph(checkInReminder())
            .paragraph("If you no longer need it, please cancel it so the next person gets it.")
            .link("See or cancel your bookings", bookingsUrl);
    send(user, "WAITLIST_PROMOTED", "From the waitlist: " + place.subject(booking), body, null);
  }

  /**
   * Cancelled bookings, one email per person (ST-4, BR-14, BR-31, AD-1).
   *
   * @param reason why, shown to the person; null when they cancelled it themselves
   */
  void cancelled(Collection<Booking> cancelled, String reason) {
    var byUser =
        cancelled.stream()
            .sorted(Comparator.comparing(Booking::getStartAt))
            .collect(
                Collectors.groupingBy(Booking::getUserId, LinkedHashMap::new, Collectors.toList()));
    byUser.forEach((userId, bookings) -> cancelledFor(lookup.user(userId), bookings, reason));
  }

  private void cancelledFor(User user, List<Booking> bookings, String reason) {
    var first = bookings.getFirst();
    var place = place(first.getRoomId());
    var body = greeting(user);
    if (bookings.size() == 1) {
      body.paragraph(
              reason == null ? "You cancelled this booking:" : "This booking has been cancelled:")
          .paragraph(place.describe(first));
    } else {
      body.paragraph(
              reason == null
                  ? "You cancelled these bookings:"
                  : "These bookings have been cancelled:")
          .list(bookings.stream().map(b -> place(b.getRoomId()).describeInline(b)).toList());
    }
    if (reason != null) {
      body.paragraph("Reason: " + reason);
    }
    body.link("See your bookings", bookingsUrl);
    var subject =
        bookings.size() == 1
            ? "Cancelled: " + place.subject(first)
            : bookings.size() + " bookings cancelled";
    send(user, "BOOKING_CANCELLED", subject, body, null);
  }

  /**
   * A student was suspended for no-shows (BR-18). The key makes it one email per suspension,
   * however many times the suspension is noticed.
   */
  void suspended(User user, Instant until, ZoneId zone) {
    var body =
        greeting(user)
            .paragraph(
                "You missed "
                    + policy.noShowLimit()
                    + " bookings in the last "
                    + policy.noShowPeriod().toDays()
                    + " days without checking in, so you cannot make new bookings until "
                    + BookingEmails.moment(until, zone)
                    + " (Singapore time).")
            .paragraph(
                "Bookings you already have are not affected. If you think this is a mistake, ask"
                    + " an admin to lift the suspension.")
            .link("See your bookings", bookingsUrl);
    send(
        user,
        "SUSPENDED",
        "Booking paused until " + BookingEmails.moment(until, zone),
        body,
        "suspended:" + user.getId() + ":" + until.toEpochMilli());
  }

  private String checkInReminder() {
    return "Check in by scanning the QR code at the room, from "
        + policy.checkInOpensBefore().toMinutes()
        + " minutes before the start until "
        + policy.noShowGrace().toMinutes()
        + " minutes after. If you do not check in, the room is released for others.";
  }

  private static BookingEmails greeting(User user) {
    return new BookingEmails().paragraph("Hi " + user.getFullName() + ",");
  }

  private void send(User user, String kind, String subject, BookingEmails body, String dedupeKey) {
    outbox.queue(
        new EmailContent(
            user.getEmail(),
            user.getFullName(),
            kind,
            subject,
            body.text(),
            body.html(),
            dedupeKey));
  }

  private Place place(UUID roomId) {
    var room = lookup.room(roomId);
    return new Place(room, lookup.facilityOf(room));
  }

  /** A room and its facility, for describing a booking. */
  private record Place(Room room, Facility facility) {

    ZoneId zone() {
      return ZoneId.of(facility.getTimeZone());
    }

    String when(Booking booking) {
      return BookingEmails.when(booking.getStartAt(), booking.getEndAt(), zone());
    }

    String describe(Booking booking) {
      return room.getName()
          + ", "
          + facility.getName()
          + "\n"
          + when(booking)
          + " (Singapore time)";
    }

    String describeInline(Booking booking) {
      return room.getName() + ", " + facility.getName() + ": " + when(booking);
    }

    String subject(Booking booking) {
      return room.getName() + ", " + BookingEmails.shortWhen(booking.getStartAt(), zone());
    }
  }
}
