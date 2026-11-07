package dev.ethanyap.slotted.booking.infrastructure;

import dev.ethanyap.slotted.booking.domain.Booking;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Booking queries. Time-based status is evaluated in the query (ADR-0004): with {@code cutoff = now
 * - no-show grace}, a booking still counts when it is checked in, or confirmed and started after
 * the cutoff. A confirmed booking that started at or before the cutoff is a no-show, even if
 * nothing has written that down yet. {@code BookingTimePolicyIntegrationTest} checks that these
 * queries agree with {@code Booking.effectiveStatus}.
 */
public interface BookingRepository
    extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

  /** The "still counts" condition, shared by the queries below. */
  String COUNTS =
      "(b.status = dev.ethanyap.slotted.booking.domain.BookingStatus.CHECKED_IN"
          + " or (b.status = dev.ethanyap.slotted.booking.domain.BookingStatus.CONFIRMED"
          + " and b.startAt > :cutoff))";

  /**
   * The settle step: records the no-shows that time has already made true in one room, so the
   * exclusion constraint stops counting them before a new booking is inserted (ADR-0004).
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE booking
             SET status = 'NO_SHOW',
                 released_at = start_at + make_interval(secs => :graceSeconds),
                 updated_at = now(),
                 version = version + 1
           WHERE room_id = :roomId
             AND status = 'CONFIRMED'
             AND start_at <= :cutoff
          """,
      nativeQuery = true)
  int settleNoShows(UUID roomId, Instant cutoff, long graceSeconds);

  @Query(
      "select count(b) > 0 from Booking b where b.roomId = :roomId"
          + " and b.startAt < :endAt and b.endAt > :startAt and "
          + COUNTS)
  boolean roomHasBookingBetween(UUID roomId, Instant startAt, Instant endAt, Instant cutoff);

  @Query(
      "select count(b) > 0 from Booking b where b.userId = :userId"
          + " and b.startAt < :endAt and b.endAt > :startAt and "
          + COUNTS)
  boolean userHasBookingBetween(UUID userId, Instant startAt, Instant endAt, Instant cutoff);

  /** Single bookings, not part of a series, that have not started yet (BR-8). */
  @Query(
      "select count(b) from Booking b where b.userId = :userId and b.startAt > :now"
          + " and b.seriesId is null"
          + " and b.status in (dev.ethanyap.slotted.booking.domain.BookingStatus.CONFIRMED,"
          + " dev.ethanyap.slotted.booking.domain.BookingStatus.CHECKED_IN)")
  long countUpcomingSingles(UUID userId, Instant now);

  /** Series with at least one date that has not started yet (BR-21). */
  @Query(
      "select count(distinct b.seriesId) from Booking b where b.userId = :userId"
          + " and b.startAt > :now and b.seriesId is not null"
          + " and b.status in (dev.ethanyap.slotted.booking.domain.BookingStatus.CONFIRMED,"
          + " dev.ethanyap.slotted.booking.domain.BookingStatus.CHECKED_IN)")
  long countUpcomingSeries(UUID userId, Instant now);

  List<Booking> findBySeriesIdOrderByStartAt(UUID seriesId);

  /** The room a booking is in, read without loading the booking, so it can be locked first. */
  @Query("select b.roomId from Booking b where b.id = :id")
  Optional<UUID> findRoomIdById(UUID id);

  /** A user's active bookings that have not started, soonest first. */
  @Query(
      "select b from Booking b where b.userId = :userId and b.startAt > :now"
          + " and b.status in (dev.ethanyap.slotted.booking.domain.BookingStatus.CONFIRMED,"
          + " dev.ethanyap.slotted.booking.domain.BookingStatus.CHECKED_IN)"
          + " order by b.startAt")
  List<Booking> findUpcoming(UUID userId, Instant now);

  /** Bookings that count toward the daily limit, starting within one local day (BR-8). */
  @Query(
      "select b from Booking b where b.userId = :userId"
          + " and b.startAt >= :dayStart and b.startAt < :dayEnd and "
          + COUNTS)
  List<Booking> findCountingStartingBetween(
      UUID userId, Instant dayStart, Instant dayEnd, Instant cutoff);

  /** Start times of no-shows, recorded or not, for the given users since a time (BR-18). */
  @Query(
      "select b.userId as userId, b.startAt as startAt from Booking b"
          + " where b.userId in :userIds and b.startAt > :since"
          + " and (b.status = dev.ethanyap.slotted.booking.domain.BookingStatus.NO_SHOW"
          + " or (b.status = dev.ethanyap.slotted.booking.domain.BookingStatus.CONFIRMED"
          + " and b.startAt <= :cutoff))")
  List<NoShow> findNoShows(Collection<UUID> userIds, Instant since, Instant cutoff);

  /** Bookings that block rooms on a timeline. */
  @Query(
      "select b from Booking b where b.roomId in :roomIds"
          + " and b.startAt < :to and b.endAt > :from and "
          + COUNTS)
  List<Booking> findCountingInRoomsBetween(
      Collection<UUID> roomIds, Instant from, Instant to, Instant cutoff);

  /** Upcoming or in progress, soonest first (ST-5). */
  @Query(
      value = "select b from Booking b where b.userId = :userId and b.endAt > :now and " + COUNTS,
      countQuery =
          "select count(b) from Booking b where b.userId = :userId and b.endAt > :now and "
              + COUNTS)
  Page<Booking> findUpcoming(UUID userId, Instant now, Instant cutoff, Pageable pageable);

  /** Everything else: finished, cancelled or no-show (ST-5). */
  @Query(
      value =
          "select b from Booking b where b.userId = :userId and not (b.endAt > :now and "
              + COUNTS
              + ")",
      countQuery =
          "select count(b) from Booking b where b.userId = :userId and not (b.endAt > :now and "
              + COUNTS
              + ")")
  Page<Booking> findPast(UUID userId, Instant now, Instant cutoff, Pageable pageable);

  /** A user's no-show, by start time. */
  interface NoShow {
    UUID getUserId();

    Instant getStartAt();
  }
}
