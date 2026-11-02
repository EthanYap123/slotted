package dev.ethanyap.slotted.booking;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ethanyap.slotted.Api;
import dev.ethanyap.slotted.IntegrationTest;
import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.EffectiveStatus;
import dev.ethanyap.slotted.booking.infrastructure.BookingFilters;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-0004 writes the time rules twice: in Java ({@code Booking.effectiveStatus}) and in the SQL
 * that lists and counts bookings. This test runs both on the same bookings at the same instant,
 * including the exact no-show boundary, and requires them to agree.
 */
@IntegrationTest
class BookingTimePolicyIntegrationTest {

  private static final Duration HOUR = Duration.ofHours(1);

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired BookingRepository bookings;
  @Autowired BookingPolicy policy;

  @Test
  void javaAndSqlAgreeOnEveryBookingsStatus() {
    var api = new Api(http);
    var admin = api.adminToken();
    var facility = api.createFacility(admin);
    var rooms = new ArrayList<UUID>();
    var user = api.newStudent().id();
    var rows = new BookingRows(jdbc);
    var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    var cutoff = now.minus(policy.noShowGrace());

    // One room per case, so active bookings that span "now" never collide.
    record Case(String state, Instant start, EffectiveStatus expected) {}
    var cases =
        List.of(
            new Case("CONFIRMED", now.plus(Duration.ofDays(1)), EffectiveStatus.CONFIRMED),
            new Case("CONFIRMED", cutoff.plusSeconds(1), EffectiveStatus.CONFIRMED),
            new Case("CONFIRMED", cutoff, EffectiveStatus.NO_SHOW),
            new Case("CONFIRMED", now.minus(Duration.ofDays(2)), EffectiveStatus.NO_SHOW),
            new Case("CHECKED_IN", now.minus(Duration.ofMinutes(50)), EffectiveStatus.CHECKED_IN),
            new Case("CHECKED_IN", now.minus(HOUR), EffectiveStatus.COMPLETED),
            new Case("CANCELLED", now.minus(Duration.ofDays(5)), EffectiveStatus.CANCELLED),
            new Case("NO_SHOW", now.minus(Duration.ofDays(6)), EffectiveStatus.NO_SHOW));

    var expected = new HashMap<String, EffectiveStatus>();
    for (var c : cases) {
      var room = UUID.fromString(createRoom(api, admin, facility, "Clock " + rooms.size()));
      rooms.add(room);
      var end = c.start().plus(HOUR);
      var id =
          switch (c.state()) {
            case "CONFIRMED" -> rows.confirmed(room.toString(), user, c.start(), end);
            case "CHECKED_IN" -> rows.checkedIn(room.toString(), user, c.start(), end);
            case "CANCELLED" -> rows.cancelled(room.toString(), user, c.start(), end);
            default -> rows.recordedNoShow(room.toString(), user, c.start(), end);
          };
      expected.put(id, c.expected());
    }

    var javaStatus =
        bookings.findAll(BookingFilters.inRooms(rooms)).stream()
            .collect(
                Collectors.toMap(b -> b.getId().toString(), b -> b.effectiveStatus(now, policy)));
    assertThat(javaStatus).isEqualTo(expected);

    for (var status : EffectiveStatus.values()) {
      Specification<Booking> filter =
          Specification.allOf(
              BookingFilters.inRooms(rooms), BookingFilters.effectiveStatus(status, now, cutoff));
      var sqlIds = bookings.findAll(filter).stream().map(b -> b.getId().toString()).toList();
      assertThat(sqlIds)
          .as("SQL filter for %s", status)
          .containsExactlyInAnyOrderElementsOf(idsWith(expected, EnumSet.of(status)));
    }

    var counting =
        bookings.findCountingInRoomsBetween(
            rooms, now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(30)), cutoff);
    assertThat(counting.stream().map(b -> b.getId().toString()))
        .as("bookings that still count")
        .containsExactlyInAnyOrderElementsOf(
            idsWith(
                expected,
                EnumSet.of(
                    EffectiveStatus.CONFIRMED,
                    EffectiveStatus.CHECKED_IN,
                    EffectiveStatus.COMPLETED)));
  }

  private static String createRoom(Api api, String admin, String facility, String name) {
    return api.post(
            "/api/v1/facilities/" + facility + "/rooms",
            Map.of("name", name, "type", "STUDY_ROOM", "capacity", 4),
            admin)
        .getBody()
        .path("id")
        .asText();
  }

  private static List<String> idsWith(
      Map<String, EffectiveStatus> expected, EnumSet<EffectiveStatus> statuses) {
    return expected.entrySet().stream()
        .filter(e -> statuses.contains(e.getValue()))
        .map(Map.Entry::getKey)
        .toList();
  }
}
