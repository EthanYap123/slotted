package dev.ethanyap.slotted.booking.domain;

import java.time.Duration;

/**
 * The configurable numbers in the booking rules (PRD section 5, values marked "config"). Defaults
 * are in application.yml.
 *
 * @param window how far ahead a booking may start (BR-3)
 * @param maxUpcoming most upcoming bookings a student may hold (BR-8)
 * @param maxDailyMinutes most booked minutes per student per local day (BR-8)
 * @param lateCancellation cancelling closer than this to the start is a late cancellation (BR-13)
 * @param checkInOpensBefore check-in opens this long before the start (BR-16)
 * @param noShowGrace a booking not checked in this long after its start is a no-show (BR-17)
 * @param noShowLimit this many no-shows within {@code noShowPeriod} suspends a student (BR-18)
 * @param noShowPeriod the period no-shows are counted over (BR-18)
 * @param suspension how long a suspension lasts (BR-18)
 */
public record BookingPolicy(
    Duration window,
    int maxUpcoming,
    int maxDailyMinutes,
    Duration lateCancellation,
    Duration checkInOpensBefore,
    Duration noShowGrace,
    int noShowLimit,
    Duration noShowPeriod,
    Duration suspension) {

  /** The values approved in the PRD, for tests. */
  public static BookingPolicy defaults() {
    return new BookingPolicy(
        Duration.ofDays(14),
        3,
        240,
        Duration.ofHours(2),
        Duration.ofMinutes(10),
        Duration.ofMinutes(15),
        3,
        Duration.ofDays(30),
        Duration.ofDays(7));
  }
}
