package dev.ethanyap.slotted.booking;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts bookings straight into the database, for states the API cannot create on purpose: a
 * booking that started an hour ago, or no-shows from last week. This is how tests "travel in time"
 * without a fake clock.
 */
final class BookingRows {

  private final JdbcTemplate jdbc;

  BookingRows(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** A confirmed booking, not checked in. */
  String confirmed(String roomId, String userId, Instant start, Instant end) {
    return insert(roomId, userId, start, end, "CONFIRMED", null);
  }

  String checkedIn(String roomId, String userId, Instant start, Instant end) {
    return insert(roomId, userId, start, end, "CHECKED_IN", start);
  }

  /** A no-show the settle step already wrote down. */
  String recordedNoShow(String roomId, String userId, Instant start, Instant end) {
    return insert(roomId, userId, start, end, "NO_SHOW", null);
  }

  String cancelled(String roomId, String userId, Instant start, Instant end) {
    var id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO booking (id, room_id, user_id, start_at, end_at, status, attendees,"
            + " cancelled_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'CANCELLED', 1, ?)",
        id,
        roomId,
        userId,
        utc(start),
        utc(end),
        utc(start.minusSeconds(3600)));
    return id;
  }

  String status(String bookingId) {
    return jdbc.queryForObject(
        "SELECT status FROM booking WHERE id = ?::uuid", String.class, bookingId);
  }

  private String insert(
      String roomId, String userId, Instant start, Instant end, String status, Instant checkedIn) {
    var id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO booking (id, room_id, user_id, start_at, end_at, status, attendees,"
            + " checked_in_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, 1, ?)",
        id,
        roomId,
        userId,
        utc(start),
        utc(end),
        status,
        checkedIn == null ? null : utc(checkedIn));
    return id;
  }

  /** Passed as OffsetDateTime so the driver never involves the JVM's default zone. */
  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
